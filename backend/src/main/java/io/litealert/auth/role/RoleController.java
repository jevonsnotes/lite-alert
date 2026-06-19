package io.litealert.auth.role;

import io.litealert.auth.permission.PermissionService;
import io.litealert.auth.permission.Permissions;
import io.litealert.common.error.BusinessException;
import io.litealert.common.error.ErrorCode;
import io.litealert.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleStore store;
    private final PermissionService permissions;

    @GetMapping("/permissions")
    public List<String> permissions() {
        permissions.require(Permissions.ROLE_VIEW);
        return Permissions.ALL;
    }

    @GetMapping
    public List<Role> list() {
        permissions.require(Permissions.ROLE_VIEW);
        return store.findAll();
    }

    @PostMapping
    public Role create(@RequestBody RoleRequest req) {
        permissions.require(Permissions.ROLE_CREATE);
        Role role = Role.builder()
                .id(IdGenerator.entityId("r"))
                .name(req.name())
                .description(req.description())
                .systemBuiltin(false)
                .permissions(req.permissions() == null ? Set.of() : req.permissions())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return store.save(role);
    }

    @PatchMapping("/{id}")
    public Role update(@PathVariable String id, @RequestBody RoleRequest req) {
        permissions.require(Permissions.ROLE_UPDATE);
        Role role = store.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "role not found"));
        if (!role.isSystemBuiltin()) {
            if (req.name() != null) role.setName(req.name());
            if (req.description() != null) role.setDescription(req.description());
        }
        if (req.permissions() != null) role.setPermissions(req.permissions());
        return store.save(role);
    }

    @DeleteMapping("/{id}")
    public java.util.Map<String, String> delete(@PathVariable String id) {
        permissions.require(Permissions.ROLE_DELETE);
        Role role = store.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "role not found"));
        if (role.isSystemBuiltin()) throw new BusinessException(ErrorCode.CONFLICT, "built-in role cannot be deleted");
        store.delete(id);
        return java.util.Map.of("status", "deleted");
    }

    public record RoleRequest(String name, String description, Set<String> permissions) {}
}
