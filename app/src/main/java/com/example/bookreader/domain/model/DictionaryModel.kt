package com.example.bookreader.domain.model

data class DictionaryResult(
    val query: String,
    val isEnglish: Boolean,
    val phonetic: String? = null,
    val translation: String? = null,
    val definitions: List<DefinitionItem> = emptyList(),
    val examples: List<ExampleSentence> = emptyList(),
    val source: String = "词典",
    val error: String? = null
)

data class DefinitionItem(
    val partOfSpeech: String? = null,
    val meaning: String,
    val examples: List<String> = emptyList()
)

data class ExampleSentence(
    val original: String,
    val translation: String
)
