package com.vchat.web;

import com.vchat.domain.AppUser;
import com.vchat.dto.Dtos.AdminUserDto;
import com.vchat.dto.Dtos.MessageDto;
import com.vchat.service.AdminService;
import com.vchat.service.Current;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) { this.admin = admin; }

    @GetMapping("/users")
    public List<AdminUserDto> users(@RequestParam(required = false) String q) { return admin.users(q); }

    @PostMapping("/users/{id}/disable")
    public AdminUserDto disable(@PathVariable UUID id) { return admin.setDisabled(Current.userId(), id, true); }

    @PostMapping("/users/{id}/enable")
    public AdminUserDto enable(@PathVariable UUID id) { return admin.setDisabled(Current.userId(), id, false); }

    @PostMapping("/users/{id}/role")
    public AdminUserDto role(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return admin.setRole(Current.userId(), id, AppUser.Role.valueOf(body.getOrDefault("role", "USER")));
    }

    @PostMapping("/messages/{id}/remove")
    public MessageDto remove(@PathVariable UUID id) { return admin.removeMessage(Current.userId(), id); }
}
