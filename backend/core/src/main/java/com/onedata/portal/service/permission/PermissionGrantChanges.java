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
            // WRITE is INSERT+UPDATE+DELETE; compare per sub-action so refining shows only the real
            // change.
            Set<String> left = expand(old.getOrDefault(key, Collections.emptySet()));
            Set<String> right = expand(next.getOrDefault(key, Collections.emptySet()));
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
            change.put("beforeActions", collapse(left));
            change.put("afterActions", collapse(right));
            change.put("addedActions", collapse(added));
            change.put("removedActions", collapse(removed));
            changes.add(change);
        }
        return changes;
    }

    private static final List<String> WRITE_PARTS = List.of("INSERT", "UPDATE", "DELETE");

    private static Set<String> expand(Set<String> actions) {
        Set<String> result = new TreeSet<>(actions);
        if (result.remove("WRITE")) result.addAll(WRITE_PARTS);
        return result;
    }

    private static List<String> collapse(Set<String> actions) {
        Set<String> result = new TreeSet<>(actions);
        if (result.containsAll(WRITE_PARTS)) {
            result.removeAll(WRITE_PARTS);
            result.add("WRITE");
        }
        return new ArrayList<>(result);
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
