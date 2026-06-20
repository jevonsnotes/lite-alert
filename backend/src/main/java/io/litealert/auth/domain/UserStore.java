package io.litealert.auth.domain;

import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserStore {

    private final UserMapper mapper;

    public Optional<User> findById(String id) {
        User u = mapper.selectOneById(id);
        return u == null ? Optional.empty() : Optional.of(u);
    }

    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        QueryWrapper qw = QueryWrapper.create()
                .where("lower(username) = lower(?)", username);
        return Optional.ofNullable(mapper.selectOneByQuery(qw));
    }

    public List<User> findAll() {
        QueryWrapper qw = QueryWrapper.create()
                .orderBy("created_at desc, username asc");
        return mapper.selectListByQuery(qw);
    }

    public boolean existsByUsername(String username) {
        QueryWrapper qw = QueryWrapper.create()
                .select()
                .where("lower(username) = lower(?)", username);
        return mapper.selectCountByQuery(qw) > 0;
    }

    public synchronized User save(User u) {
        if (findById(u.getId()).isPresent()) {
            mapper.update(u);
        } else {
            mapper.insert(u);
        }
        return u;
    }

    public synchronized void delete(String id) {
        mapper.deleteById(id);
    }
}
