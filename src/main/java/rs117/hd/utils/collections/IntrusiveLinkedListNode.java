package rs117.hd.utils.collections;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class IntrusiveLinkedListNode {
	final AtomicReference<IntrusiveLinkedListNode> next = new AtomicReference<>(null);
	final AtomicInteger version = new AtomicInteger(0);
}
