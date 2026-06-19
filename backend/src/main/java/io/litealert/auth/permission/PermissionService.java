package io.litealert.auth.permission;

import io.litealert.auth.CurrentUser;
import io.litealert.auth.domain.User;
import io.litealert.auth.role.RoleStore;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final CurrentUser currentUser;
    private final RoleStore roleStore;

    public Set<String> permissions(String userId) {
        Set<String> out = new LinkedHashSet<>();
        for (var role : roleStore.findByUser(userId)) out.addAll(role.getPermissions());
        return out;
    }

    public Set<String> currentPermissions() {
        User user = currentUser.getOrThrow();
        Set<String> out = permissions(user.getId());
        if (user.getPermissions() != null) user.getPermissions().forEach(p -> out.add(p.name()));
        return out;
    }

    public boolean has(String permission) { return currentPermissions().contains(permission); }

    public void require(String permission) {
        if (!has(permission)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
