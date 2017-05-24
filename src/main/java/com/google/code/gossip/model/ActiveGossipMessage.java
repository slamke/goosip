package com.google.code.gossip.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Gossip各个节点之间发送和接收的消息体
 */
public class ActiveGossipMessage {

    private List<GossipMember> members = new ArrayList<>();

    public ActiveGossipMessage() {

    }

    public List<GossipMember> getMembers() {
        return members;
    }

    public void setMembers(List<GossipMember> members) {
        this.members = members;
    }

}
