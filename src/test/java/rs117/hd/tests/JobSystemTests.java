package rs117.hd.tests;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.utils.jobs.JobGenericTask;
import rs117.hd.utils.jobs.JobHandle;
import rs117.hd.utils.jobs.JobSystem;

@Slf4j
public class JobSystemTests {

	@BeforeClass
	public static void beforeAll() {
		JobSystem.INSTANCE = new JobSystem();
		JobSystem.INSTANCE.initialize();
	}

	@AfterClass
	public static void afterAll() {
		JobSystem.INSTANCE.shutdown();
	}

	@Test
	public void testQueueAndCompletion() throws Exception {
		final AtomicBoolean ranToCompletion = new AtomicBoolean(false);

		JobGenericTask task = JobGenericTask.build(
			"testQueueAndCompletion",
			(t) -> busyWork(t, 5000, ranToCompletion)
		);

		JobHandle handle = task.queue(false);

		handle.await(true);

		Assert.assertTrue(ranToCompletion.get());
	}

	@Test
	public void testQueueWithMultipleDependencies() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean ranA = new AtomicBoolean(false);
		JobHandle hA = JobGenericTask.build("A", t -> {
				busyWork(t, 200, ranA);
				order.add("A");
			}).queue();

		AtomicBoolean ranB = new AtomicBoolean(false);
		JobHandle hB = JobGenericTask.build("B", t -> {
				busyWork(t, 300, ranB);
				order.add("B");
			}).queue(hA);

		AtomicBoolean ranC = new AtomicBoolean(false);
		JobHandle hC = JobGenericTask.build("C", t -> {
				busyWork(t, 300, ranC);
				order.add("C");
			}).queue(hA, hB);

		// Wait for dependent
		hC.await();

		Assert.assertTrue(hA.isCompleted());
		Assert.assertTrue(hB.isCompleted());
		Assert.assertTrue(hC.isCompleted());

		int idxA = order.indexOf("A");
		int idxB = order.indexOf("B");
		int idxC = order.indexOf("C");

		Assert.assertTrue(idxA >= 0 && idxB >= 0 && idxC >= 0);
		Assert.assertTrue(idxC > idxA && idxC > idxB);
		Assert.assertTrue(ranA.get() && ranB.get() && ranC.get());
	}

	@Test
	public void testCancelTask() throws Exception {
		final AtomicBoolean ranToCompletion = new AtomicBoolean(false);

		JobGenericTask longTask = JobGenericTask.build(
			"long",
			(t) -> busyWork(t, 10000, ranToCompletion)
		);

		JobHandle h = longTask.queue();

		Thread.sleep(50); // let it start
		h.cancel(true);

		h.await();

		Assert.assertTrue(h.isCompleted());
		Assert.assertFalse(ranToCompletion.get());
	}

	@Test
	public void testTasksWideParallel() throws Exception {
		int taskCount = 1000;

		List<AtomicBoolean> executedFlags = new CopyOnWriteArrayList<>();
		List<JobHandle> handles = new CopyOnWriteArrayList<>();

		// Queue all tasks with no dependencies
		for (int i = 0; i < taskCount; i++) {
			AtomicBoolean done = new AtomicBoolean(false);
			executedFlags.add(done);

			int idx = i;
			JobHandle h = JobGenericTask.build(
				"Task" + idx,
				t -> {
					busyWork(t, 100, done); // short work for speed
				}).queue();

			handles.add(h);
		}

		// Wait for all tasks to complete
		for (JobHandle h : handles) {
			h.await();
		}

		// Verify all tasks ran
		for (int i = 0; i < taskCount; i++) {
			Assert.assertTrue("Task" + i + " should have executed", executedFlags.get(i).get());
		}
	}

	@Test
	public void testManyDependenciesStress() throws Exception {
		int count = 500;
		List<AtomicBoolean> flags = new CopyOnWriteArrayList<>();
		List<JobHandle> handles = new CopyOnWriteArrayList<>();

		JobHandle prev = null;

		for (int i = 0; i < count; i++) {
			AtomicBoolean done = new AtomicBoolean(false);
			flags.add(done);

			int idx = i;
			JobHandle h = JobGenericTask.build("T" + idx, t -> {
					log.debug("[TASK {}] Start", idx);
					busyWork(t, 10, done);
				}).queue(prev == null ? new JobHandle[0] : new JobHandle[]{prev} );

			prev = h;
			handles.add(h);
		}

		prev.await();

		for (int i = 0; i < count; i++)
			Assert.assertTrue("Task T" + i + " should complete", flags.get(i).get());
	}

	@Test
	public void testDependencyChain() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		AtomicBoolean cDone = new AtomicBoolean(false);

		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 200, aDone);
				order.add("A");
			}).queue();

		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 200, bDone);
				order.add("B");
			}).queue(hA);

		JobHandle hC = JobGenericTask.build("C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 200, cDone);
				order.add("C");
			}).queue(hB);

		hC.await();

		Assert.assertEquals(List.of("A", "B", "C"), order);
		Assert.assertTrue(aDone.get() && bDone.get() && cDone.get());
	}

	@Test
	public void testBranchingDependencies() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		AtomicBoolean cDone = new AtomicBoolean(false);
		AtomicBoolean dDone = new AtomicBoolean(false);

		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 100, aDone);
				order.add("A");
			}).queue();

		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 200, bDone);
				order.add("B");
			}).queue(hA);

		JobHandle hC = JobGenericTask.build("C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 150, cDone);
				order.add("C");
			}).queue(hA);

		JobHandle hD = JobGenericTask.build("D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 100, dDone);
				order.add("D");
			}).queue(hB, hC);

		hD.await();

		Assert.assertTrue(order.indexOf("A") < order.indexOf("B"));
		Assert.assertTrue(order.indexOf("A") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("D"));
		Assert.assertTrue(order.indexOf("C") < order.indexOf("D"));
	}

	@Test
	public void testDiamondDependencyGraph() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		AtomicBoolean cDone = new AtomicBoolean(false);
		AtomicBoolean dDone = new AtomicBoolean(false);
		AtomicBoolean eDone = new AtomicBoolean(false);

		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 120, aDone);
				order.add("A");
			}).queue();

		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 140, bDone);
				order.add("B");
			}).queue();

		JobHandle hC = JobGenericTask.build("C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 200, cDone);
				order.add("C");
			}).queue(hA, hB);

		JobHandle hD = JobGenericTask.build("D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 180, dDone);
				order.add("D");
			}).queue(hA, hB);

		JobHandle hE = JobGenericTask.build("E", t -> {
				log.debug("[TASK E] Start");
				busyWork(t, 80, eDone);
				order.add("E");
			}).queue(hC, hD);

		hE.await();

		Assert.assertTrue(order.indexOf("A") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("C"));
		Assert.assertTrue(order.indexOf("A") < order.indexOf("D"));
		Assert.assertTrue(order.indexOf("B") < order.indexOf("D"));

		Assert.assertTrue(order.indexOf("C") < order.indexOf("E"));
		Assert.assertTrue(order.indexOf("D") < order.indexOf("E"));
	}

	@Test
	public void testCancelUpstreamDependencyPreventsDownstreamExecution() throws Exception {
		final AtomicBoolean aDone = new AtomicBoolean(false);
		final AtomicBoolean bDone = new AtomicBoolean(false);

		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 5000, aDone);
			}).queue();

		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start (This shouldn't happen)");
				busyWork(t, 100, bDone);
			}).queue(hA);

		Thread.sleep(50);
		hA.cancel(true);
		hB.await();

		Assert.assertFalse(bDone.get());
	}

	@Test
	public void testCircularDependencyDetection() {
		// Create 4 fresh handles
		JobHandle A = JobHandle.obtain();
		JobHandle B = JobHandle.obtain();
		JobHandle C = JobHandle.obtain();
		JobHandle D = JobHandle.obtain();

		// ----------------------------
		// Case 1: Valid chain A → B → C → D
		// Should NOT throw
		// ----------------------------
		try {
			A.addDependency(B); // A → B
			B.addDependency(C); // B → C
			C.addDependency(D); // C → D
		} catch (Exception ex) {
			Assert.fail("Valid dependency chain threw unexpectedly: " + ex);
		}

		// ----------------------------
		// Case 2: Create a cycle by adding D → A
		// A → B → C → D → A  (cycle)
		// Should throw IllegalStateException
		// ----------------------------
		boolean cycleThrown = false;
		try {
			D.addDependency(A);
		} catch (IllegalStateException expected) {
			cycleThrown = true;
		}
		Assert.assertTrue("Cycle A → B → C → D → A should throw IllegalStateException", cycleThrown);


		// ----------------------------
		// Case 3: Self-cycle A → A
		// Should throw IllegalStateException
		// ----------------------------
		boolean selfCycleThrown = false;
		try {
			A.addDependency(A);
		} catch (IllegalStateException expected) {
			selfCycleThrown = true;
		}
		Assert.assertTrue("Self-cycle A → A should throw IllegalStateException", selfCycleThrown);
	}

	@Test
	public void testGroupedDependencyGraph() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		AtomicBoolean cDone = new AtomicBoolean(false);
		AtomicBoolean dDone = new AtomicBoolean(false);
		AtomicBoolean eDone = new AtomicBoolean(false);

		// ----- A -----
		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 100, aDone);
				order.add("A");
			}).queue();

		// ----- B -----
		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 120, bDone);
				order.add("B");
			}).queue();

		// ----- C depends on A & B -----
		JobHandle hC = JobGenericTask.build("C", t -> {
				log.debug("[TASK C] Start");
				busyWork(t, 150, cDone);
				order.add("C");
			}).queue(hA, hB);

		// ----- D depends on C -----
		JobHandle hD = JobGenericTask.build("D", t -> {
				log.debug("[TASK D] Start");
				busyWork(t, 80, dDone);
				order.add("D");
			}).queue(hC);

		// ----- E depends on C -----
		JobHandle hE = JobGenericTask.build("E", t -> {
				log.debug("[TASK E] Start");
				busyWork(t, 90, eDone);
				order.add("E");
			}).queue(hC);

		// Wait for the final downstream tasks
		hD.await();
		hE.await();

		// ----- Assertions -----
		Assert.assertTrue(order.indexOf("A") >= 0);
		Assert.assertTrue(order.indexOf("B") >= 0);
		Assert.assertTrue(order.indexOf("C") >= 0);
		Assert.assertTrue(order.indexOf("D") >= 0);
		Assert.assertTrue(order.indexOf("E") >= 0);

		// C must run after both A and B
		Assert.assertTrue(order.indexOf("C") > order.indexOf("A"));
		Assert.assertTrue(order.indexOf("C") > order.indexOf("B"));

		// D & E must run after C
		Assert.assertTrue(order.indexOf("D") > order.indexOf("C"));
		Assert.assertTrue(order.indexOf("E") > order.indexOf("C"));

		// Flags must be set
		Assert.assertTrue(aDone.get());
		Assert.assertTrue(bDone.get());
		Assert.assertTrue(cDone.get());
		Assert.assertTrue(dDone.get());
		Assert.assertTrue(eDone.get());
	}

	@Test
	public void testCancelUpstreamCascadesDownstream() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();

		AtomicBoolean aDone = new AtomicBoolean(false);
		AtomicBoolean bDone = new AtomicBoolean(false);
		AtomicBoolean cDone = new AtomicBoolean(false);
		AtomicBoolean dDone = new AtomicBoolean(false);
		AtomicBoolean eDone = new AtomicBoolean(false);

		// ----- A: Will be cancelled -----
		JobHandle hA = JobGenericTask.build("A", t -> {
				log.debug("[TASK A] Start");
				busyWork(t, 3000, aDone);  // long-running, gives us time to cancel
				order.add("A");
			}).queue();

		// ----- B: Allowed to run -----
		JobHandle hB = JobGenericTask.build("B", t -> {
				log.debug("[TASK B] Start");
				busyWork(t, 100, bDone);
				order.add("B");
			}).queue();

		// ----- C depends on A & B -----
		JobHandle hC = JobGenericTask.build("C", t -> {
				log.debug("[TASK C] Should NOT execute");
				busyWork(t, 50, cDone);
				order.add("C");
			}).queue(hA, hB);

		// ----- D depends on C -----
		JobHandle hD = JobGenericTask.build("D", t -> {
				log.debug("[TASK D] Should NOT execute");
				busyWork(t, 50, dDone);
				order.add("D");
			}).queue(hC);

		// ----- E depends on C -----
		JobHandle hE = JobGenericTask.build("E", t -> {
				log.debug("[TASK E] Should NOT execute");
				busyWork(t, 50, eDone);
				order.add("E");
			}).queue(hC);

		// Let A start
		Thread.sleep(50);
		hA.cancel(true); // cancel upstream root

		// Wait for final tasks
		hE.await();

		// ----- Assertions -----

		// B is not cancelled, should run
		hB.await();
		Assert.assertTrue(bDone.get());

		// A is cancelled
		Assert.assertFalse(aDone.get());

		// C, D, E must never execute because they depend on A
		Assert.assertFalse(cDone.get());
		Assert.assertFalse(dDone.get());
		Assert.assertFalse(eDone.get());

		// Order should contain only B (A is cancelled before completing)
		Assert.assertTrue(order.contains("B"));
		Assert.assertEquals(1, order.size());
	}

	private static void busyWork(JobGenericTask task, long millis, AtomicBoolean executed) throws InterruptedException {
		final long start = System.nanoTime();
		final long durationNanos = millis * 1_000_000L;
		long nextLogTime = System.nanoTime() + 1000 * 1_000_000L; // 1 Second

		long number = 2;
		long primesFound = 0;

		while (System.nanoTime() - start < durationNanos) {
			if (isPrime(number))
				primesFound++;

			number++;

			task.workerHandleCancel();

			long now = System.nanoTime();
			if (now >= nextLogTime) {
				log.debug("busyWork: checked={} primes={}", number, primesFound);
				nextLogTime = now + 1000 * 1_000_000L;
			}
		}

		executed.set(true);
	}

	private static boolean isPrime(long n) {
		if (n < 2) return false;
		if (n % 2 == 0) return n == 2;

		long limit = (long) Math.sqrt(n);
		for (long i = 3; i <= limit; i += 2) {
			if (n % i == 0)
				return false;
		}
		return true;
	}
}
