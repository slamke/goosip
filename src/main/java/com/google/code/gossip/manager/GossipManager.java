/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.code.gossip.manager;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.Notification;
import javax.management.NotificationListener;

import org.apache.log4j.Logger;

import com.google.code.gossip.GossipMember;
import com.google.code.gossip.GossipService;
import com.google.code.gossip.GossipSettings;
import com.google.code.gossip.LocalGossipMember;
import com.google.code.gossip.event.GossipListener;
import com.google.code.gossip.event.GossipState;

/**
 * 调度local 节点，进行消息处理
 * Executor框架运行passive和active 线程
 */
public abstract class GossipManager extends Thread implements NotificationListener {

    public static final Logger LOGGER = Logger.getLogger(GossipManager.class);

    public static final int MAX_PACKET_SIZE = 102400;

    private final ConcurrentSkipListMap<LocalGossipMember, GossipState> members;

    private final LocalGossipMember me;

    private final GossipSettings settings;

    private final AtomicBoolean gossipServiceRunning;

    private final Class<? extends PassiveGossipThread> passiveGossipThreadClass;

    private final Class<? extends ActiveGossipThread> activeGossipThreadClass;

    private final GossipListener listener;

    private ActiveGossipThread activeGossipThread;

    private PassiveGossipThread passiveGossipThread;

    private ExecutorService gossipThreadExecutor;

    public GossipManager(Class<? extends PassiveGossipThread> passiveGossipThreadClass,
                         Class<? extends ActiveGossipThread> activeGossipThreadClass, String cluster,
                         String address, int port, String id, GossipSettings settings,
                         List<GossipMember> gossipMembers, GossipListener listener) {
        this.passiveGossipThreadClass = passiveGossipThreadClass;
        this.activeGossipThreadClass = activeGossipThreadClass;
        this.settings = settings;
        me = new LocalGossipMember(cluster, address, port, id, System.currentTimeMillis(), this,
                settings.getCleanupInterval());
        members = new ConcurrentSkipListMap<>();
        for (GossipMember startupMember : gossipMembers) {
            if (!startupMember.equals(me)) {
                LocalGossipMember member = new LocalGossipMember(startupMember.getClusterName(),
                        startupMember.getHost(), startupMember.getPort(), startupMember.getId(),
                        System.currentTimeMillis(), this, settings.getCleanupInterval());
                members.put(member, GossipState.UP);
                GossipService.LOGGER.debug(member);
            }
        }
        gossipThreadExecutor = Executors.newCachedThreadPool();
        gossipServiceRunning = new AtomicBoolean(true);
        this.listener = listener;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                GossipService.LOGGER.debug("Service has been shutdown...");
            }
        }));
    }

    /**
     * All timers associated with a member will trigger this method when it goes off. The timer will
     * go off if we have not heard from this member in <code> _settings.T_CLEANUP </code> time.
     */
    @Override
    public void handleNotification(Notification notification, Object handback) {
        LocalGossipMember deadMember = (LocalGossipMember) notification.getUserData();
        GossipService.LOGGER.debug("Dead member detected: " + deadMember);
        members.put(deadMember, GossipState.DOWN);
        if (listener != null) {
            listener.gossipEvent(deadMember, GossipState.DOWN);
        }
    }

    // 接收到节点消息，进行reset处理
    public void revivieMember(LocalGossipMember m) {
        // 失效历史timer
        for (Entry<LocalGossipMember, GossipState> it : this.members.entrySet()) {
            if (it.getKey().getId().equals(m.getId())) {
                it.getKey().disableTimer();
            }
        }
        members.remove(m);
        members.put(m, GossipState.UP);
        if (listener != null) {
            listener.gossipEvent(m, GossipState.UP);
        }
    }

    public void createOrRevivieMember(LocalGossipMember m) {
        members.put(m, GossipState.UP);
        if (listener != null) {
            listener.gossipEvent(m, GossipState.UP);
        }
    }

    public GossipSettings getSettings() {
        return settings;
    }

    /**
     * @return a read only list of members found in the UP state
     */
    public List<LocalGossipMember> getMemberList() {
        List<LocalGossipMember> up = new ArrayList<>();
        for (Entry<LocalGossipMember, GossipState> entry : members.entrySet()) {
            if (GossipState.UP.equals(entry.getValue())) {
                up.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(up);
    }

    public LocalGossipMember getMyself() {
        return me;
    }

    public List<LocalGossipMember> getDeadList() {
        List<LocalGossipMember> up = new ArrayList<>();
        for (Entry<LocalGossipMember, GossipState> entry : members.entrySet()) {
            if (GossipState.DOWN.equals(entry.getValue())) {
                up.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(up);
    }

    /**
     * Starts the client. Specifically, start the various cycles for this protocol. Start the gossip
     * thread and start the receiver thread.
     */
    public void run() {
        for (LocalGossipMember member : members.keySet()) {
            if (member != me) {
                member.startTimeoutTimer();
            }
        }
        try {
            passiveGossipThread = passiveGossipThreadClass.getConstructor(GossipManager.class)
                    .newInstance(this);
            gossipThreadExecutor.execute(passiveGossipThread);
            activeGossipThread = activeGossipThreadClass.getConstructor(GossipManager.class)
                    .newInstance(this);
            gossipThreadExecutor.execute(activeGossipThread);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e1) {
            throw new RuntimeException(e1);
        }
        GossipService.LOGGER.debug("The GossipService is started.");
        while (gossipServiceRunning.get()) {
            try {
                // TODO
                TimeUnit.MILLISECONDS.sleep(1);
            } catch (InterruptedException e) {
                GossipService.LOGGER.warn("The GossipClient was interrupted.");
            }
        }
    }

    /**
     * Shutdown the gossip service.
     */
    public void shutdown() {
        gossipServiceRunning.set(false);
        gossipThreadExecutor.shutdown();
        if (passiveGossipThread != null) {
            passiveGossipThread.shutdown();
        }
        if (activeGossipThread != null) {
            activeGossipThread.shutdown();
        }
        try {
            boolean result = gossipThreadExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
            if (!result) {
                LOGGER.error("executor shutdown timed out");
            }
        } catch (InterruptedException e) {
            LOGGER.error(e);
        }
    }
}
