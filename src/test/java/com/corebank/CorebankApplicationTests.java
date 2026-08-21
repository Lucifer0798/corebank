package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.repository.AccountRepository;
import com.corebank.config.CoreBankProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CorebankApplicationTests {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CoreBankProperties properties;

    @Test
    @DisplayName("the context starts and the migrations leave the general ledger in place")
    void contextLoadsWithGeneralLedgerSeeded() {
        assertThat(accounts.findByAccountNumber(properties.ledger().cashAccountNumber()))
                .describedAs("the cash general-ledger account must exist for any posting to balance")
                .isPresent();
        assertThat(accounts.findByAccountNumber(properties.ledger().suspenseAccountNumber())).isPresent();
    }
}
