package rs117.hd.utils.collections;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

// CAS IntrusiveLinkedList implementation
// https://www.data-structures-in-practice.com/intrusive-linked-lists/
// https://dzone.com/articles/demystifying-javas-compare-and-swap-cas

@SuppressWarnings("unchecked")
public final class IntrusiveLinkedList<T extends IntrusiveLinkedListNode> implements Iterable<T> {
	private final AtomicReference<T> head = new AtomicReference<>(null);
	private final AtomicInteger count = new AtomicInteger(0);

	public void push(T node) {
		while (true) {
			T oldHead = head.get();
			node.next.set(oldHead);
			if (head.compareAndSet(oldHead, node)) {
				node.version.incrementAndGet(); // bump version after successful CAS
				count.incrementAndGet();
				return;
			}
		}
	}

	public T pop() {
		while (true) {
			T oldHead = head.get();
			if (oldHead == null) return null;
			T next = (T) oldHead.next.get();
			if (head.compareAndSet(oldHead, next)) {
				oldHead.next.set(null);
				count.decrementAndGet();
				return oldHead;
			}
		}
	}

	public boolean remove(T target) {
		while (true) {
			T prev = null;
			T current = head.get();

			while (current != null) {
				T next = (T) current.next.get();
				if (current == target) {
					if (prev == null) {
						// removing head
						if (head.compareAndSet(current, next)) {
							current.next.set(null);
							count.decrementAndGet();
							return true;
						}
						break; // retry outer loop
					} else {
						// removing middle node
						if (prev.next.compareAndSet(current, next)) {
							current.next.set(null);
							count.decrementAndGet();
							return true;
						}
						break; // retry outer loop
					}
				}
				prev = current;
				current = next;
			}
			if (current == null) return false;
		}
	}

	public boolean contains(T target) {
		for (T current = head.get(); current != null; current = (T) current.next.get())
			if (current == target) return true;
		return false;
	}

	public void clear() {
		head.set(null);
		count.set(0);
	}

	public int size() { return count.get(); }

	public boolean isEmpty() { return size() == 0; }

	public void forEach(Consumer<? super T> action) {
		for (T current = head.get(); current != null; current = (T) current.next.get())
			action.accept(current);
	}

	@Override
	@Nonnull
	public Iterator<T> iterator() {
		return new Iterator<>() {
			private T current = head.get();
			private T lastReturned = null;

			@Override
			public boolean hasNext() {
				return current != null;
			}

			@Override
			public T next() {
				if (current == null) throw new NoSuchElementException();
				lastReturned = current;
				current = (T) current.next.get();
				return lastReturned;
			}

			@Override
			public void remove() {
				if (lastReturned == null) throw new IllegalStateException();
				IntrusiveLinkedList.this.remove(lastReturned);
				lastReturned = null;
			}
		};
	}
}