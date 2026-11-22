package com.example.bookreader.data.dictionary

import android.util.LruCache
import com.example.bookreader.domain.model.DefinitionItem
import com.example.bookreader.domain.model.DictionaryResult
import com.example.bookreader.domain.model.ExampleSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DictionaryService {

    private val cache = LruCache<String, DictionaryResult>(150)

    suspend fun lookup(rawQuery: String): DictionaryResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return@withContext DictionaryResult(
                query = "",
                isEnglish = false,
                error = "请输入要查询的字词或语句"
            )
        }

        // 优先读取内存缓存
        cache.get(query.lowercase())?.let { return@withContext it }

        val hasChinese = query.any { it in '\u4e00'..'\u9fa5' }
        val isEnglish = !hasChinese

        try {
            val result = queryYoudao(query, isEnglish)
            if (result != null && (result.definitions.isNotEmpty() || !result.translation.isNullOrBlank())) {
                cache.put(query.lowercase(), result)
                return@withContext result
            }

            // 词典未直接收录（如长句、口语短语），调用在线翻译服务
            val fallbackResult = queryTranslationFallback(query, isEnglish)
            cache.put(query.lowercase(), fallbackResult)
            fallbackResult
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果网络异常但词语在离线字典中有记录或降级提示
            DictionaryResult(
                query = query,
                isEnglish = isEnglish,
                error = "查询失败：请检查网络连接 (${e.localizedMessage ?: "网络超时"})"
            )
        }
    }

    private fun queryYoudao(query: String, isEnglish: Boolean): DictionaryResult? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://dict.youdao.com/jsonapi?q=$encoded"
        val jsonStr = httpGet(urlStr) ?: return null

        val json = JSONObject(jsonStr)
        val definitions = mutableListOf<DefinitionItem>()
        val examples = mutableListOf<ExampleSentence>()
        var phonetic: String? = null
        var translationSummary: String? = null
        var sourceName = if (isEnglish) "有道英汉词典" else "汉语规范词典"

        if (isEnglish) {
            // 英文 -> 中文解析
            val ec = json.optJSONObject("ec")
            val wordObj = ec?.optJSONArray("word")?.optJSONObject(0)
            if (wordObj != null) {
                phonetic = wordObj.optString("usphone").takeIf { it.isNotEmpty() }
                    ?: wordObj.optString("phone").takeIf { it.isNotEmpty() }
                    ?: json.optJSONObject("simple")?.optJSONArray("word")?.optJSONObject(0)?.optString("phone")

                val trs = wordObj.optJSONArray("trs")
                if (trs != null) {
                    for (i in 0 until trs.length()) {
                        val tr = trs.optJSONObject(i)?.optJSONArray("tr")?.optJSONObject(0)
                        val line = tr?.optJSONObject("l")?.optJSONArray("i")?.optString(0)?.trim()
                        if (!line.isNullOrEmpty()) {
                            val parsed = parsePosAndMeaning(line)
                            definitions.add(parsed)
                        }
                    }
                }
            }

            // 补充柯林斯词典释义（若 ec 释义较少）
            if (definitions.isEmpty()) {
                val collins = json.optJSONObject("collins")
                val collinsEntries = collins?.optJSONArray("collins_entries")
                if (collinsEntries != null && collinsEntries.length() > 0) {
                    sourceName = "柯林斯高阶英汉双解词典"
                    val entry = collinsEntries.optJSONObject(0)
                    phonetic = phonetic ?: entry.optString("phonetic")
                    val entriesObj = entry.optJSONObject("entries")
                    val subEntries = entriesObj?.optJSONArray("entry")
                    if (subEntries != null) {
                        for (i in 0 until subEntries.length()) {
                            val sub = subEntries.optJSONObject(i)
                            val tran = sub?.optJSONObject("tran_entry")?.optString("tran")
                            val pos = sub?.optJSONObject("tran_entry")?.optString("pos_entry")
                            if (!tran.isNullOrEmpty()) {
                                definitions.add(DefinitionItem(partOfSpeech = pos, meaning = tran))
                            }
                        }
                    }
                }
            }

            // 双语例句
            val blngSents = json.optJSONObject("blng_sents_part")?.optJSONArray("sentence-pair")
            if (blngSents != null) {
                for (i in 0 until minOf(blngSents.length(), 4)) {
                    val pair = blngSents.optJSONObject(i) ?: continue
                    val eng = stripHtml(pair.optString("sentence-eng"))
                    val cn = stripHtml(pair.optString("sentence-translation"))
                    if (eng.isNotEmpty() && cn.isNotEmpty()) {
                        examples.add(ExampleSentence(original = eng, translation = cn))
                    }
                }
            }
        } else {
            // 中文字典 / 成语 / 词语解析
            val newhh = json.optJSONObject("newhh")
            val dataList = newhh?.optJSONArray("dataList")
            if (dataList != null && dataList.length() > 0) {
                val item = dataList.optJSONObject(0)
                phonetic = item.optString("pinyin").takeIf { it.isNotEmpty() }
                sourceName = newhh.optJSONObject("source")?.optString("name") ?: "《现代汉语规范词典》"

                val senses = item.optJSONArray("sense")
                if (senses != null) {
                    for (i in 0 until senses.length()) {
                        val s = senses.optJSONObject(i) ?: continue
                        val cat = s.optString("cat").takeIf { it.isNotEmpty() }
                        val defArray = s.optJSONArray("def")
                        val defTexts = mutableListOf<String>()
                        if (defArray != null) {
                            for (d in 0 until defArray.length()) {
                                val text = defArray.optString(d)
                                if (text.isNotEmpty()) defTexts.add(text)
                            }
                        }
                        val meaning = defTexts.joinToString("； ")

                        val exArray = s.optJSONArray("examples")
                        val exList = mutableListOf<String>()
                        if (exArray != null) {
                            for (e in 0 until exArray.length()) {
                                val exStr = stripHtml(exArray.optString(e))
                                if (exStr.isNotEmpty()) exList.add(exStr)
                            }
                        }

                        if (meaning.isNotEmpty()) {
                            definitions.add(
                                DefinitionItem(
                                    partOfSpeech = cat,
                                    meaning = meaning,
                                    examples = exList
                                )
                            )
                        }
                    }
                }
            }

            // 成语 / 词条百科与历史典故
            val baikeSummary = json.optJSONObject("baike")?.optJSONArray("summarys")?.optJSONObject(0)?.optString("summary")
            if (!baikeSummary.isNullOrEmpty()) {
                if (definitions.isEmpty()) {
                    sourceName = "成语典故词典"
                    definitions.add(DefinitionItem(partOfSpeech = "成语", meaning = stripHtml(baikeSummary)))
                } else {
                    examples.add(ExampleSentence(original = "成语出处与典故", translation = stripHtml(baikeSummary)))
                }
            }

            // 汉英词义补充 (如将中文词汇翻译为对应英文)
            val ceWord = json.optJSONObject("ce")?.optJSONArray("word")?.optJSONObject(0)
            phonetic = phonetic ?: ceWord?.optString("phone")
            val ceTrs = ceWord?.optJSONArray("trs")
            if (ceTrs != null && ceTrs.length() > 0) {
                val transList = mutableListOf<String>()
                for (i in 0 until minOf(ceTrs.length(), 4)) {
                    val trObj = ceTrs.optJSONObject(i)?.optJSONArray("tr")?.optJSONObject(0)
                    val en = trObj?.optJSONObject("l")?.optJSONArray("i")?.optString(0)?.trim()
                    if (!en.isNullOrEmpty()) transList.add(en)
                }
                if (transList.isNotEmpty()) {
                    translationSummary = "英译参考: " + transList.joinToString(", ")
                }
            }
        }

        if (definitions.isEmpty() && translationSummary == null) {
            return null
        }

        return DictionaryResult(
            query = query,
            isEnglish = isEnglish,
            phonetic = phonetic?.let { if (it.startsWith("[") || it.startsWith("/")) it else "[$it]" },
            translation = translationSummary ?: definitions.firstOrNull()?.meaning,
            definitions = definitions,
            examples = examples,
            source = sourceName
        )
    }

    private fun queryTranslationFallback(query: String, isEnglish: Boolean): DictionaryResult {
        val langPair = if (isEnglish) "en|zh-CN" else "zh-CN|en"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"
        val jsonStr = httpGet(urlStr)

        if (jsonStr != null) {
            val json = JSONObject(jsonStr)
            val translated = json.optJSONObject("responseData")?.optString("translatedText")
            if (!translated.isNullOrEmpty()) {
                val clean = stripHtml(translated)
                return DictionaryResult(
                    query = query,
                    isEnglish = isEnglish,
                    translation = clean,
                    definitions = listOf(
                        DefinitionItem(
                            partOfSpeech = if (isEnglish) "翻译" else "Translation",
                            meaning = clean
                        )
                    ),
                    source = "在线翻译引擎"
                )
            }
        }

        return DictionaryResult(
            query = query,
            isEnglish = isEnglish,
            error = "未在词典中找到“$query”的释义"
        )
    }

    private fun parsePosAndMeaning(raw: String): DefinitionItem {
        // 例: "n. 苹果；苹果树" -> pos="n.", meaning="苹果；苹果树"
        val dotIndex = raw.indexOf(". ")
        return if (dotIndex in 1..8) {
            val pos = raw.substring(0, dotIndex + 1).trim()
            val meaning = raw.substring(dotIndex + 2).trim()
            DefinitionItem(partOfSpeech = pos, meaning = meaning)
        } else {
            DefinitionItem(partOfSpeech = null, meaning = raw)
        }
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
