package com.onedata.portal.service.permission;

import com.onedata.portal.service.permission.PermissionModels.GrantSelection;

import java.util.*;

/** Business resource changes; native drift remains visible in the preview SQL and warnings. */
final class PermissionGrantChanges {
    private PermissionGrantChanges() {}

    static List<Map<String, Object>> between(
            List<GrantSelection> before, List<GrantSelection> after) {
        Map<List<String>, Set<String>> old = group(before), next = group(after);
        Set<List<String>> keys = new LinkedHashSet<>(old.keySet());
        keys.addAll(next.keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (List<String> key : keys) {
            Set<String> left = old.getOrDefault(key, Collections.emptySet());
            Set<String> right = next.getOrDefault(key, Collections.emptySet());
            if (left.equals(right)) continue;
            Set<String> added = new TreeSet<>(right), removed = new TreeSet<>(left);
            added.removeAll(left);
            removed.removeAll(right);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put(
                    "changeType", left.isEmpty() ? "ADD" : right.isEmpty() ? "REMOVE" : "UPDATE");
            change.put("resourceType", key.get(0));
            change.put("database", key.get(1));
            change.put("tableName", key.get(2));
            change.put("beforeActions", new ArrayList<>(left));
            change.put("afterActions", new ArrayList<>(right));
            change.put("addedActions", new ArrayList<>(added));
            change.put("removedActions", new ArrayList<>(removed));
            changes.add(change);
        }
        return changes;
    }

    private static Map<List<String>, Set<String>> group(List<GrantSelection> grants) {
        Map<List<String>, Set<String>> result = new LinkedHashMap<>();
        for (GrantSelection grant : grants) {
            List<String> key =
                    Arrays.asList(
                            grant.getResourceType(),
                            grant.getDatabase(),
                            "SCHEMA".equals(grant.getResourceType())
                                    ? ""
                                    : Objects.toString(grant.getTableName(), ""));
            result.computeIfAbsent(key, ignored -> new TreeSet<>()).addAll(grant.getActions());
        }
        return result;
    }
}
