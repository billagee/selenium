// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.grid.internal.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.openqa.grid.common.RegistrationRequest.APP;
import static org.openqa.grid.common.RegistrationRequest.CLEAN_UP_CYCLE;
import static org.openqa.grid.common.RegistrationRequest.ID;
import static org.openqa.grid.common.RegistrationRequest.MAX_SESSION;
import static org.openqa.grid.common.RegistrationRequest.TIME_OUT;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.DetachedRemoteProxy;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.listeners.TestSessionListener;
import org.openqa.grid.internal.listeners.TimeoutListener;
import org.openqa.grid.internal.mock.GridHelper;
import org.openqa.grid.web.servlet.handler.RequestHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SessionListenerTest {

  static class MyRemoteProxy extends DetachedRemoteProxy implements TestSessionListener {

    public MyRemoteProxy(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void afterSession(TestSession session) {
      session.put("FLAG", false);

    }

    public void beforeSession(TestSession session) {
      session.put("FLAG", true);
    }
  }

  static RegistrationRequest req = null;
  static Map<String, Object> app1 = new HashMap<String, Object>();

  @BeforeClass
  public static void prepare() {
    app1.put(APP, "app1");
    Map<String, Object> config = new HashMap<String, Object>();
    config.put(ID, "abc");
    req = new RegistrationRequest();
    req.addDesiredCapability(app1);
    req.setConfiguration(config);

  }

  @Test
  public void beforeAfterRan() {
    Registry registry = Registry.newInstance();
    registry.add(new MyRemoteProxy(req, registry));

    RequestHandler req = GridHelper.createNewSessionHandler(registry, app1);

    req.process();
    TestSession session = req.getSession();
    assertEquals(true, session.get("FLAG"));
    registry.terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);
    try {
      Thread.sleep(250);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    assertEquals(false, session.get("FLAG"));
  }

  /**
   * buggy proxy that will throw an exception the first time beforeSession is called.
   *
   * @author Francois Reynaud
   */
  static class MyBuggyBeforeRemoteProxy extends DetachedRemoteProxy implements TestSessionListener {

    private boolean firstCall = true;

    public MyBuggyBeforeRemoteProxy(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void afterSession(TestSession session) {}

    public void beforeSession(TestSession session) {
      if (firstCall) {
        firstCall = false;
        throw new NullPointerException();
      }
    }
  }

  /**
   * if before throws an exception, the resources are released for other tests to use.
   */
  @Test(timeout = 500000)
  public void buggyBefore() throws InterruptedException {
    Registry registry = Registry.newInstance();
    registry.add(new MyBuggyBeforeRemoteProxy(req, registry));

    RequestHandler req = GridHelper.createNewSessionHandler(registry, app1);
    try {
      req.process();
    } catch (Exception ignore) {
      // the listener exception will bubble up.
    }

    // reserve throws an exception, that calls session.terminate, which is
    // in a separate thread. Gives some time for this thread to finish
    // before doing the validations
    while (registry.getActiveSessions().size() != 0) {
      Thread.sleep(250);
    }

    assertEquals(registry.getActiveSessions().size(), 0);

    RequestHandler req2 = GridHelper.createNewSessionHandler(registry, app1);
    req2.process();

    TestSession session = req2.getSession();
    assertNotNull(session);
    assertEquals(registry.getActiveSessions().size(), 1);

  }

  /**
   * buggy proxy that will throw an exception the first time beforeSession is called.
   *
   * @author Francois Reynaud
   */
  static class MyBuggyAfterRemoteProxy extends DetachedRemoteProxy implements TestSessionListener {

    public MyBuggyAfterRemoteProxy(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void afterSession(TestSession session) {
      throw new NullPointerException();
    }

    public void beforeSession(TestSession session) {}
  }

  static volatile boolean processed = false;

  /**
   * if after throws an exception, the resources are NOT released got other tests to use.
   */
  @Test(timeout = 1000)
  public void buggyAfter() throws InterruptedException {
    Registry registry = Registry.newInstance();
    try {
      registry.add(new MyBuggyAfterRemoteProxy(req, registry));

      RequestHandler req = GridHelper.createNewSessionHandler(registry, app1);
      req.process();
      TestSession session = req.getSession();
      assertEquals(registry.getActiveSessions().size(), 1);
      assertNotNull(session);
      registry.terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      final RequestHandler req2 = GridHelper.createNewSessionHandler(registry, app1);

      new Thread(new Runnable() { // Thread safety reviewed

            public void run() {
              req2.process();
              processed = true;
            }
          }).start();

      Thread.sleep(100);
      assertFalse(processed);
    } finally {
      registry.stop();
    }
  }

  class SlowAfterSession extends DetachedRemoteProxy implements TestSessionListener, TimeoutListener {

    private Lock lock = new ReentrantLock();
    private boolean firstTime = true;

    public SlowAfterSession(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void afterSession(TestSession session) {
      session.put("after", true);
      try {
        lock.lock();
        if (firstTime) {
          firstTime = false;
        } else {
          session.put("ERROR", "called twice ..");
        }

      } finally {
        lock.unlock();
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    public void beforeSession(TestSession session) {}

    public void beforeRelease(TestSession session) {
      getRegistry().terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);
    }
  }

  /**
   * using a proxy that times out instantly and spends a long time in the after method. check
   * aftermethod cannot be excecuted twice for a session.
   */
  @Test
  public void doubleRelease() throws InterruptedException {
    RegistrationRequest req = new RegistrationRequest();
    Map<String, Object> cap = new HashMap<String, Object>();
    cap.put(APP, "app1");

    Map<String, Object> config = new HashMap<String, Object>();
    config.put(TIME_OUT, 1);
    config.put(CLEAN_UP_CYCLE, 1);
    config.put(MAX_SESSION, 2);
    config.put(ID, "abc");


    req.addDesiredCapability(cap);
    req.setConfiguration(config);

    Registry registry = Registry.newInstance();
    try {
      final SlowAfterSession proxy = new SlowAfterSession(req, registry);
      proxy.setupTimeoutListener();
      registry.add(proxy);

      RequestHandler r = GridHelper.createNewSessionHandler(registry, app1);
      r.process();
      TestSession session = r.getSession();

      Thread.sleep(150);
      // the session has timed out -> doing the long after method.
      assertEquals(session.get("after"), true);

      // manually closing the session, starting a 2nd release process.
      registry.terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);

      // the 2nd release process shouldn't be executed as one is already
      // processed.
      assertNull(session.get("ERROR"));
    } finally {
      registry.stop();
    }

  }

}
