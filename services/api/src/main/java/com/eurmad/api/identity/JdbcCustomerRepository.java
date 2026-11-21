package com.eurmad.api.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCustomerRepository implements CustomerRepository {

    private final JdbcTemplate jdbc;

    JdbcCustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerProfile> findByOidcIdentity(String oidcIssuer, String oidcSubject) {
        return jdbc.query(
                        "SELECT id, residency_country, kyc_status, status FROM customers"
                                + " WHERE oidc_issuer = ? AND oidc_subject = ?",
                        (rs, row) -> new CustomerProfile(
                                rs.getObject("id", UUID.class),
                                rs.getString("residency_country"),
                                rs.getString("kyc_status"),
                                rs.getString("status")),
                        oidcIssuer,
                        oidcSubject)
                .stream()
                .findFirst();
    }
}
