/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.vk.training

/**
 * Voice Keyboard: the voice training program. Read sentences (exact transcript known) and free talk (the user
 * corrects the transcript) are recorded, transcribed by the user's server for feedback, and uploaded as
 * dictation record + audio, so every second counts toward the server's Whisper fine-tuning.
 *
 * The script is generic (no personal data in the code); the user's own learned words from the server are
 * mixed in at run time.
 */
object VkTraining {
    /** Minutes of voice the server wants before it fine-tunes. */
    const val TARGET_MINUTES = 120

    /** Sentences per reading round (about 3-4 minutes with feedback). */
    const val ROUND_SIZE = 10

    val SENTENCES = listOf(
        // Everyday messages
        "I'm running about ten minutes late, so start without me.",
        "Can you pick up milk, eggs, and bread on the way home?",
        "Let's meet at the coffee shop on Main Street at noon.",
        "Thanks for your help today, I really appreciate it.",
        "Don't forget the dentist appointment on Thursday at three thirty.",
        "The kids have soccer practice after school until five.",
        "Could you send me the photos from the weekend?",
        "I'll call you back after the meeting finishes.",
        "Dinner is in the oven, it should be ready by six fifteen.",
        "We need to leave by seven if we want to beat the traffic.",
        // Numbers, dates, times, money
        "The total came to forty two dollars and seventy five cents.",
        "My flight leaves on March twenty first at eight forty in the morning.",
        "Set the thermostat to sixty eight degrees tonight.",
        "The package should arrive between the second and the fifth of June.",
        "Our order number is four eight one five one six two three.",
        "Please call me at five five five, one two three, four five six seven.",
        "The meeting moved from Tuesday the ninth to Wednesday the tenth.",
        "It's about a quarter mile past the second traffic light.",
        // Questions and punctuation
        "Are you coming to the party on Saturday, or do you have plans?",
        "Wow, that was a great game last night!",
        "First, preheat the oven. Second, mix the flour and sugar. Third, add the eggs.",
        "She asked, \"Where did you put the keys?\"",
        "Wait, did you lock the front door before you left?",
        // Home and tech
        "Turn off the living room lights and lock the back door.",
        "The server needs a reboot after the update finishes installing.",
        "Back up the photos to the network drive before Friday.",
        "The Wi-Fi in the garage keeps dropping, can you check the access point?",
        "Restart the router and see if the connection comes back.",
        "The dashboard shows the solar panels made twelve kilowatt hours today.",
        "Remind me to replace the furnace filter next month.",
        // Longer, natural sentences
        "Honestly, I think we should wait until next week before we decide anything.",
        "The weather looks nice this weekend, so maybe we could go for a hike.",
        "If the store is out of the blue one, the green one is fine too.",
        "I read the report this morning and most of it looks good to me.",
        "Let me know what time works for you and I'll put it on the calendar.",
        "Quick brown foxes jump over lazy dogs near the quiet river bank.",
        "Please review the attached document and send me your comments by Friday.",
        "We should probably order more coffee before we run out again.",
        "The car is making a strange noise when I turn left at low speed.",
        "Happy birthday! I hope you have a wonderful day with the family.",
    )

    /** Sentences that use the user's learned words, so the training covers their own names and terms. */
    fun personalSentences(words: List<String>): List<String> {
        val templates = listOf(
            "Can you ask %s to call me back this afternoon?",
            "I talked to %s about it yesterday.",
            "Please add %s to the list for next week.",
            "The note about %s is on the kitchen counter.",
        )
        return words.filter { it.isNotBlank() && it.length in 2..30 }.distinct().take(8)
            .mapIndexed { i, w -> templates[i % templates.size].format(w) }
    }

    /** One reading round: a few of the user's own words mixed into a fresh set of sentences. */
    fun round(learnedWords: List<String>, seed: Long = System.currentTimeMillis()): List<String> {
        val rnd = java.util.Random(seed)
        val personal = personalSentences(learnedWords).shuffled(rnd).take(3)
        val general = SENTENCES.shuffled(rnd).take(ROUND_SIZE - personal.size)
        return (general + personal).shuffled(rnd)
    }

    val FREE_TALK_PROMPTS = listOf(
        "Describe what you did today, from the morning until now.",
        "Text a family member that you're running late and what time you'll be home.",
        "Explain how to get from your house to your favourite restaurant.",
        "Talk about a project you're working on and what's left to do.",
        "Describe your ideal weekend.",
    )

    /** Words for comparison: lowercase, letters and digits only. */
    fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Word error rate of [heard] against [expected] (0.0 = perfect), and the expected words that were missed. */
    fun score(expected: String, heard: String): Pair<Double, Set<Int>> {
        val e = words(expected)
        val h = words(heard)
        if (e.isEmpty()) return 0.0 to emptySet()
        // Levenshtein over words, with a backtrace for which expected words were not matched.
        val d = Array(e.size + 1) { IntArray(h.size + 1) }
        for (i in 0..e.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..e.size) for (j in 1..h.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (e[i - 1] == h[j - 1]) 0 else 1)
        }
        val missed = mutableSetOf<Int>()
        var i = e.size
        var j = h.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && e[i - 1] == h[j - 1] && d[i][j] == d[i - 1][j - 1] -> { i--; j-- }
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> { missed.add(i - 1); i--; j-- }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { missed.add(i - 1); i-- }
                else -> j--
            }
        }
        return d[e.size][h.size].toDouble() / e.size to missed
    }
}
