package parseGame


class Condition(val opCode: Int, val param1: Int, val param2: Int)

class Action(val opCode: Int, val param1: Int, val param2: Int, val next: Action?) {
    companion object {
        fun read(memory: IntArray, ptr: Int): Action? {
            val opCode = memory[ptr]
            if (opCode != 0xff) {
                val param1 = memory[ptr + 1]
                val param2 = memory[ptr + 2]
                // This assumes dbVer == 0!
                val nArgs = when (opCode) {
                    in 0..10 -> 0
                    in 11..19, 21, 22 -> 1
                    else -> 2
                }
                return Action(opCode, param1, param2, read(memory, ptr + 1 + nArgs))
            } else return null
        }
    }
}

class Command(val word1: Int, val word2: Int, val conditions: List<Condition>, val action: Action?, val next: Command?)

class GameData(memory: IntArray) {
    var locations: List<String>
    var nobjectsCarried: Int
    var messages: List<String>
    var vocabulary: Map<String, Int>
    var responses: Command?
    var processes: Command?
    var objects: List<String>
    var initialObjectPositions: List<Int>
    var connections: Array<Map<Int, Int>>

    init {
        fun word(ptr: Int) = memory[ptr] + 256 * memory[ptr + 1]

        fun readString(start: Int): Pair<String, Int> {
            var ptr = start
            var s = ""
            var actLength = 0

            val colors = arrayOf("#000", "#00f", "#f00", "#f0f", "#0f0", "#0ff", "#ff0", "#fff")
            val mappedChars = mapOf(
                '&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '\u0060' to "&pound;", '\u007f' to "&copy;",
                *listOf(
                    "Ž",
                    "<span style=\"position:relative\">T<span style=\"position: absolute; left: 0\">ž</span></span>>",
                    "Č", "đ", "š", "č", "SI", "", "ž", "Ž", "NC", "LA",
                    "<span style=\"position:relative\">M<span style=\"position: relative; left: -0.4em\">K</span></span>>",
                    "IR", "ö", "ß", "ž", "ä", "Š", "ć", "ü", "RND", "INKEY$", "PI", "FN ", "POINT "
                ).mapIndexed { i, repl -> (i + 0x90).toChar() to repl }.toTypedArray()
            )

            fun readNext() = memory[ptr++]

            fun addChar(c: String) {
                if (actLength == 32) {
                    if (c.last() != ' ') {
                        s += ' '
                    }
                    actLength = 0
                }
                s += c
                actLength++
            }

            fun addChar(c: Char) = addChar(c.toString())
            fun addChar(c: Int) = addChar(c.toChar())

            while (true) {
                val c = 255 - readNext()
                val mapped = mappedChars[c.toChar()]
                if (mapped != null) {
                    addChar(mapped)
                } else when (c) {
                    0x1f -> return Pair(s, ptr)
                    in 32..255 -> addChar(c)
                    0x06 -> {
                        if (255 - memory[ptr] == 6) {
                            addChar("<p>")
                            ptr++
                        } else {
                            addChar(' ')
                        }
                        actLength = 0
                    }
                    0x10 -> {
                        val color = 255 - readNext()
                        if (color < 8) {
                            s += "<font color=${colors[color]}>"
                        }
                    }
                    0x11 -> readNext() // PAPER --- implement
                }
            }
        }

        fun getStrings(pstart: Int, n: Int): List<String> {
            var ptr = word(pstart)
            return List(n) msg@{
                val res = readString(ptr)
                ptr = res.second
                return@msg res.first
            }
        }

        fun getVocabulary(start: Int): Map<String, Int> {
            var ptr = start
            val res = mutableMapOf<String, Int>()
            while (memory[ptr + 4] != 0) {
                res[memory
                    .slice(ptr..ptr + 3)
                    .map { (255 - it).toChar() }
                    .joinToString("")
                    .trim()
                ] = memory[ptr + 4]
                ptr += 5
            }
            return res
        }

        fun getConditionsActions(start: Int): Pair<List<Condition>, Action?> {
            var ptr = start
            val conditions = mutableListOf<Condition>()
            while (memory[ptr] != 0xff) {
                val opCode = memory[ptr]
                val param1 = memory[ptr + 1]
                val param2 = if (opCode > 12) memory[ptr + 2] else -1
                conditions.add(Condition(opCode, param1, param2))
                ptr += if (opCode > 12) 3 else 2
            }
            ptr++

            val action = Action.read(memory, ptr)
            return Pair(conditions, action)
        }

        fun getCommands(ptr: Int, processes: Boolean): Command? {
            fun getWord(i: Int) = if (processes) 255 else memory[ptr + i]
            if (memory[ptr] != 0) {
                val condAct = getConditionsActions(word(ptr + 2))
                return Command(
                    getWord(0), getWord(1), condAct.first, condAct.second,
                    getCommands(ptr + 4, processes)
                )
            } else return null
        }

        fun readConnections(pstart: Int, n: Int): Array<Map<Int, Int>> {
            var ptr = word(pstart)
            return Array(n) dirs@{
                val dirs = mutableMapOf<Int, Int>()
                while (memory[ptr] != 0xff) {
                    dirs[memory[ptr]] = memory[ptr + 1]
                    ptr += 2
                }
                ptr += 1
                return@dirs dirs
            }
        }

        val sign = (16384..memory.size)
            .firstOrNull {
                    ptr -> (1..5).all { memory[ptr + 2 * it] == 16 + it } }
            ?: throw Exception("Quill signature not found")

        val ptr = sign + 13
        nobjectsCarried = memory[ptr]
        val nObjects = memory[ptr + 1]
        val nLocations = memory[ptr + 2]
        val nMessages = memory[ptr + 3]
        responses = getCommands(word(ptr + 4), false)
        processes = getCommands(word(ptr + 6), true)
        objects = getStrings(word(ptr + 8), nObjects)
        locations = getStrings(word(ptr + 10), nLocations)
        messages = getStrings(word(ptr + 12), nMessages)
        connections = readConnections(word(ptr + 14), nLocations)
        vocabulary = getVocabulary(word(ptr + 16))
        initialObjectPositions = memory.slice(word(ptr + 18)..word(ptr + 18) + nObjects)
    }
}
