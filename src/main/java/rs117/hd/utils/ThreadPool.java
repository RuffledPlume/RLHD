package rs117.hd.utils;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.inject.Singleton;

@Singleton
public class ThreadPool {
	class TaskPool {
		private final Object[] constuctor_params = new Object[1];
		private final Class<?> taskClass;
		private final Constructor taskConstructor;
		private final Set<Runnable> tasks = new HashSet<>();

		TaskPool(Class<?> taskClass) {
			this.taskClass = taskClass;
			final Constructor<?>[] constructors = taskClass.getConstructors();
			if(constructors.length == 1) {
				taskConstructor = constructors[0];
			}else {
				throw new RuntimeException("Task class must have one default constructor");
			}
		}

		@SuppressWarnings("unchecked")
		public <T extends Runnable> T  obtainTask(Object parent)  {
			// synchronized since releaseTask could be accessing the set at the same time
			synchronized (tasks) {
				Iterator<Runnable> iter = tasks.iterator();
				if (iter.hasNext()) {
					Runnable task = iter.next();
					iter.remove();
					return (T) task;
				}
			}
			try {
				// We found no task, so lets construct a new one
				constuctor_params[0] = parent;
				T task = (T) taskConstructor.newInstance(constuctor_params);
				constuctor_params[0] = null;
				return task;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public void releaseTask(Runnable task) {
			if(task.getClass() == this.taskClass) {
				// synchronized since obtainTask could be accessing the set at the same time
				synchronized (tasks) {
					tasks.add(task);
				}
			}
		}
	}

	class TaskWrapper implements Runnable {
		private CountDownLatch dependencyLatch;
		private Runnable dependencyWork;
		private boolean autoRelease;
		public Runnable work;

		public TaskWrapper() {};

		private void checkDependency() {
			if(dependencyWork != null && dependencyWork != work){
				TaskWrapper dependencyWrapper = findWrapperForTask(dependencyWork);
				try {
					if(dependencyWrapper != null && dependencyWrapper != this) {
						dependencyWrapper.dependencyLatch.await();
					}
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}

		@Override
		public void run() {
			if(work != null) {
				checkDependency();
				work.run();
				dependencyLatch.countDown();
				if(autoRelease) {
					releaseTask(work);
				}
			}
			releaseTask(this);
		}
	}

	private int actualThreadCount = 0;
	private final int fixedThreadCount;
	private final ExecutorService executorService;
	private final Map<Class<?>, TaskPool> taskPoolMap = new HashMap<>();
	private final Set<TaskWrapper> inflightWrappers = new HashSet<>();
	private final TaskPool taskWrapperPool = getTaskPool(TaskWrapper.class);

	public ThreadPool() {
		fixedThreadCount = Runtime.getRuntime().availableProcessors() - 1; // Minus 1 since we want to avoid context switching the client thread
		executorService = Executors.newFixedThreadPool(fixedThreadCount, r -> {
			Thread thread = new Thread(r);
			thread.setName("ThreadPool-" + actualThreadCount++);
			thread.setPriority(Thread.MAX_PRIORITY);
			return thread;
		});
	}

	public int getFixedThreadCount() {
		return fixedThreadCount;
	}

	private TaskPool getTaskPool(Class<?> taskClass) {
		TaskPool pool = taskPoolMap.get(taskClass);
		if(pool == null) {
			pool = new TaskPool(taskClass);
			taskPoolMap.put(taskClass, pool);
		}
		return pool;
	}

	public <T extends Runnable> T obtainTask(Class<T> taskClass) {
		return getTaskPool(taskClass).obtainTask(null);
	}

	public <T extends Runnable> T obtainTask(Class<T> taskClass, Object parent) {
		return getTaskPool(taskClass).obtainTask(parent);
	}

	private TaskWrapper findWrapperForTask(Runnable task){
		if(task != null) {
			synchronized (inflightWrappers) {
				for(TaskWrapper wrapper : inflightWrappers){
					if(wrapper.work == task) {
						return wrapper;
					}
				}
			}
		}
		return null;
	}

	public void releaseTask(Runnable task) {
		if(task != null) {
			Class<?> taskClass = task.getClass();
			TaskPool workPool = taskPoolMap.get(taskClass);
			if (workPool != null) {
				workPool.releaseTask(task);
			}

			if(taskClass == TaskWrapper.class){
				synchronized (inflightWrappers) {
					inflightWrappers.remove(task);
				}
			}
		}
	}

	public void submitWork(Runnable task) {
		submitWork(task, null, true);
	}

	public void submitWork(Runnable task, Runnable dependency) {
		submitWork(task, dependency, true);
	}

	public void submitWork(Runnable task, boolean autoRelease) {
		submitWork(task, null, autoRelease);
	}

	public void submitWork(Runnable task, Runnable dependency, boolean autoRelease) {
		TaskWrapper wrapper = taskWrapperPool.obtainTask(this);
		wrapper.dependencyLatch = new CountDownLatch(1);
		wrapper.dependencyWork = dependency;
		wrapper.autoRelease = autoRelease;
		wrapper.work = task;
		synchronized (inflightWrappers) {
			inflightWrappers.add(wrapper);
		}
		executorService.execute(wrapper);
	}
}
