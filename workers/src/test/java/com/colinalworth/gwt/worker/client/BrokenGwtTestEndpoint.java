package com.colinalworth.gwt.worker.client;

import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.GWT;
import com.google.gwt.junit.client.GWTTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by colin on 1/14/16.
 */
public class BrokenGwtTestEndpoint extends GWTTestCase {
	@Override
	public String getModuleName() {
		return "com.colinalworth.gwt.worker.RpcToWorkers";
	}

	public interface MyHost extends Endpoint<MyHost, MyWorker> {
		void ping();
	}
	public interface MyWorker extends Endpoint<MyWorker, MyHost> {
		void pong();

		void split(String input, String pattern, Callback<List<String>, Throwable> callback);
	}
	public interface MyWorkerFactory extends WorkerFactory<MyWorker, MyHost> {
		MyWorkerFactory instance = GWT.create(WorkerFactory.class);
	}


	public void testSimpleEndpoint() {
		delayTestFinish(1000);

		MyWorker worker = MyWorkerFactory.instance.createDedicatedWorker("simpleWorker.js", new MyHost() {
			@Override
			public void ping() {
				remote.pong();
			}

			private MyWorker remote;
			@Override
			public void setRemote(MyWorker myWorker) {
				remote = myWorker;
				remote.split("a,b,c", ",", new Callback<List<String>, Throwable>() {
					@Override
					public void onFailure(Throwable throwable) {
						fail(throwable.getMessage());
					}

					@Override
					public void onSuccess(List<String> strings) {
						List<String> expected = new ArrayList<String>();
						expected.add("a");
						expected.add("b");
						expected.add("c");
						assertEquals(expected, strings);
						finishTest();
					}
				});
			}

			@Override
			public MyWorker getRemote() {
				return remote;
			}
		});
	}
}