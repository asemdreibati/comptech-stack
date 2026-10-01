package io.souqly.inventory;

import java.util.Arrays;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class TestTokens {

    static List<GrantedAuthority> permissions(String... permissions) {
        return Arrays.stream(permissions).map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p)).toList();
    }

    private TestTokens() {
    }
}
