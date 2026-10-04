package com.prizeflux.campaign.infrastructure.persistence;

import com.prizeflux.campaign.application.AppUser;
import com.prizeflux.campaign.application.AppUserRepository;
import com.prizeflux.campaign.application.UsernameAlreadyRegisteredException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

/** 账号只存数据库生成的 ID 与密码哈希，不将哈希返回给 Web 响应。 */
@Repository
@ConditionalOnProperty(name = "prizeflux.persistence.campaign.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAppUserRepository implements AppUserRepository {
    private final JdbcTemplate jdbc;

    public JdbcAppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, role, state FROM app_user WHERE username = ?",
                (rs, row) -> new AppUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), AppUser.Role.valueOf(rs.getString("role")),
                        AppUser.State.valueOf(rs.getString("state"))), username).stream().findFirst();
    }

    @Override
    public long create(String username, String passwordHash, AppUser.Role role) {
        return insert(username, passwordHash, role);
    }

    @Override
    public long registerUser(String username, String passwordHash) {
        try {
            return insert(username, passwordHash, AppUser.Role.USER);
        } catch (DuplicateKeyException ex) {
            // 预查询不能消除并发竞争；唯一索引是最终裁决，且不向调用方泄露 SQL。
            throw new UsernameAlreadyRegisteredException();
        }
    }

    private long insert(String username, String passwordHash, AppUser.Role role) {
        // 与迁移中的约束保持一致；参数化 SQL 避免把用户名和哈希拼入语句。
        new AppUser(1, username, passwordHash, role, AppUser.State.ACTIVE);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO app_user (username, password_hash, role, state) VALUES (?, ?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null || id.longValue() <= 0) {
            throw new IllegalStateException("Account ID was not generated");
        }
        return id.longValue();
    }
}
