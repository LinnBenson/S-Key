package io.secure.keyboard.engine

/** Session-owned iterator. Only requested pages and deduplication keys stay in memory. */
internal class CandidatePager<T>(source: Sequence<T>) {
    private var iterator: Iterator<T>? = source.iterator()
    fun next(size: Int = 40): List<T> {
        val values = ArrayList<T>(size)
        val current = iterator ?: return values
        while (values.size < size && current.hasNext()) values.add(current.next())
        if (values.size < size) iterator = null
        return values
    }
    val exhausted get() = iterator == null
}
