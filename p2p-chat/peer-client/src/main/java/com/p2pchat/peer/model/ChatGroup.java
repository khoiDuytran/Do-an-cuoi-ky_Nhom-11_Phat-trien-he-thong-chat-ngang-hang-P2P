package com.p2pchat.peer.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ChatGroup implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String groupId;
    private String groupName;
    private String ownerPeerId;
    private List<String> memberPeerIds;
    private LocalDateTime createdAt;

    public ChatGroup() {
        this.memberPeerIds = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
    }

    public ChatGroup(String groupId, String groupName, String ownerPeerId) {
        this();
        this.groupId = groupId;
        this.groupName = groupName;
        this.ownerPeerId = ownerPeerId;
        this.memberPeerIds.add(ownerPeerId);
    }

    public void addMember(String peerId) {
        if (!memberPeerIds.contains(peerId)) {
            memberPeerIds.add(peerId);
        }
    }

    public void removeMember(String peerId) {
        memberPeerIds.remove(peerId);
    }

    public boolean hasMember(String peerId) {
        return memberPeerIds.contains(peerId);
    }

    // Getters & Setters
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getOwnerPeerId() { return ownerPeerId; }
    public void setOwnerPeerId(String ownerPeerId) { this.ownerPeerId = ownerPeerId; }

    public List<String> getMemberPeerIds() { return memberPeerIds; }
    public void setMemberPeerIds(List<String> memberPeerIds) { this.memberPeerIds = memberPeerIds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() { return groupName; }
}