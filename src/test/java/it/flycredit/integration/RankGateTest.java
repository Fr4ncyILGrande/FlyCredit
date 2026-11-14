package it.flycredit.integration;

import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RankGateTest {
    private final Set<String> allowed = Set.of("cercatore", "elite");
    private User user(String group, boolean expired, boolean contextMatches) {
        User user = mock(User.class);
        InheritanceNode node = mock(InheritanceNode.class);
        ImmutableContextSet context = mock(ImmutableContextSet.class);
        ImmutableContextSet current = mock(ImmutableContextSet.class);
        QueryOptions query = mock(QueryOptions.class);
        when(user.getQueryOptions()).thenReturn(query);
        Group effective = mock(Group.class);
        when(effective.getName()).thenReturn(group);
        when(user.getInheritedGroups(query)).thenReturn(List.of(effective));
        when(query.context()).thenReturn(current);
        when(user.getNodes(NodeType.INHERITANCE)).thenReturn(List.of(node));
        when(node.getValue()).thenReturn(true);
        when(node.getGroupName()).thenReturn(group);
        when(node.hasExpired()).thenReturn(expired);
        when(node.getContexts()).thenReturn(context);
        when(context.isSatisfiedBy(current)).thenReturn(contextMatches);
        return user;
    }
    @Test void onlyTheTwoDirectGroupsAreAllowed() {
        assertTrue(PlayTimeHook.allowed(user("cercatore", false, true), allowed, false));
        assertTrue(PlayTimeHook.allowed(user("ELITE", false, true), allowed, false));
        assertFalse(PlayTimeHook.allowed(user("admin", false, true), allowed, false));
        assertFalse(PlayTimeHook.allowed(user("default", false, true), allowed, false));
    }
    @Test void expiredAndWrongContextGroupsAreDenied() {
        assertFalse(PlayTimeHook.allowed(user("elite", true, true), allowed, false));
        assertFalse(PlayTimeHook.allowed(user("elite", false, false), allowed, false));
    }
    @Test void inheritedRankDoesNotAdmitOtherDirectRanksByDefault() {
        User u = user("staff", false, true);
        assertFalse(PlayTimeHook.allowed(u, allowed, false));
        Group inherited = mock(Group.class);
        when(inherited.getName()).thenReturn("cercatore");
        when(u.getInheritedGroups(any())).thenReturn(List.of(inherited));
        assertFalse(PlayTimeHook.allowed(u, allowed, false));
        assertTrue(PlayTimeHook.allowed(u, allowed, true));
    }
    @Test void rawParentNodeCannotOverrideEffectiveContextualMembership() {
        User u = user("elite", false, true);
        when(u.getInheritedGroups(any())).thenReturn(List.of());
        assertFalse(PlayTimeHook.allowed(u, allowed, false));
        assertFalse(PlayTimeHook.allowed(u, allowed, true));
    }
}
