package rs117.hd.scene.model_overrides;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AhslPredicateCache implements ModelOverride.AhslPredicate {
	private static final int MAX_CACHE_SIZE = 512;

	private final ModelOverride.AhslPredicate predicate;

	// Sorted by ahsl (lower 32 bits), eviction via age counter
	private long[] cache = new long[16];
	private int cacheSize = 0;
	private int ageCounter = 0;

	private int getCachedResult(int ahsl) {
		int lo = 0, hi = cacheSize - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			long midEntry = cache[mid];
			int midAhsl = (int) midEntry;

			if (midAhsl < ahsl) {
				lo = mid + 1;
			} else if (midAhsl > ahsl) {
				hi = mid - 1;
			} else {
				return (int) ((midEntry >>> 32) & 1);
			}
		}
		return -1;
	}

	private void putCachedResult(int ahsl, boolean result) {
		// Prevent age overflow from breaking eviction ordering
		if (ageCounter == Integer.MAX_VALUE)
			ageCounter = 0;

		long entry =
			(
				(((long) ++ageCounter) << 1) |
				(result ? 1L : 0L)
			) << 32 |
			(ahsl & 0xFFFFFFFFL);

		// Grow backing array if needed
		if (cacheSize < MAX_CACHE_SIZE && cacheSize >= cache.length)
			cache = Arrays.copyOf(cache, Math.min(cache.length * 2, MAX_CACHE_SIZE));

		if (cacheSize < MAX_CACHE_SIZE) {
			// Insert directly
			insertSorted(entry, ahsl);
			return;
		}

		// Cache full: evict oldest by age
		int oldestIndex = 0;
		long oldestAge = cache[0] >>> 33;

		for (int i = 1; i < cacheSize; i++) {
			long age = cache[i] >>> 33;
			if (age < oldestAge) {
				oldestAge = age;
				oldestIndex = i;
			}
		}

		// Remove oldest entry
		System.arraycopy(
			cache, oldestIndex + 1,
			cache, oldestIndex,
			cacheSize - oldestIndex - 1
		);
		cacheSize--;

		// Reinsert new entry to maintain sorted order
		insertSorted(entry, ahsl);
	}

	private void insertSorted(long entry, int ahsl) {
		int lo = 0, hi = cacheSize - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int midAhsl = (int) cache[mid];
			if (midAhsl < ahsl) {
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		System.arraycopy(cache, lo, cache, lo + 1, cacheSize - lo);
		cache[lo] = entry;
		cacheSize++;
	}

	@Override
	public boolean test(int ahsl) {
		int cached = getCachedResult(ahsl);
		if (cached != -1)
			return cached == 1;

		boolean result = predicate.test(ahsl);
		putCachedResult(ahsl, result);
		return result;
	}
}
