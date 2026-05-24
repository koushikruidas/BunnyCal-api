package com.daedalussystems.easySchedule.calendar.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MicrosoftAccountClassifierTest {

    @Test
    void outlookCom_isPersonalMsa_andBackendIcsFallback() {
        MicrosoftAccountClassifier.Classification c =
                MicrosoftAccountClassifier.classifyByEmail("user@outlook.com");
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA, c.accountClassification());
        assertEquals(MicrosoftAccountClassifier.DELIVERY_BACKEND_ICS_FALLBACK, c.organizerInviteDelivery());
    }

    @Test
    void hotmailCom_isPersonalMsa() {
        MicrosoftAccountClassifier.Classification c =
                MicrosoftAccountClassifier.classifyByEmail("USER@hotmail.com");
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA, c.accountClassification());
    }

    @Test
    void liveDomain_isPersonalMsa() {
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA,
                MicrosoftAccountClassifier.classifyByEmail("alice@live.com").accountClassification());
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA,
                MicrosoftAccountClassifier.classifyByEmail("alice@live.co.uk").accountClassification());
    }

    @Test
    void msnDomain_isPersonalMsa() {
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA,
                MicrosoftAccountClassifier.classifyByEmail("bob@msn.com").accountClassification());
    }

    @Test
    void aadOnmicrosoftDomain_isAadAndProviderNative() {
        MicrosoftAccountClassifier.Classification c =
                MicrosoftAccountClassifier.classifyByEmail("admin@contoso.onmicrosoft.com");
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_AAD_WORK_SCHOOL, c.accountClassification());
        assertEquals(MicrosoftAccountClassifier.DELIVERY_PROVIDER_NATIVE, c.organizerInviteDelivery());
    }

    @Test
    void aadCustomDomain_isAad() {
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_AAD_WORK_SCHOOL,
                MicrosoftAccountClassifier.classifyByEmail("user@contoso.com").accountClassification());
    }

    @Test
    void nullEmail_isUnknown() {
        MicrosoftAccountClassifier.Classification c = MicrosoftAccountClassifier.classifyByEmail(null);
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_UNKNOWN, c.accountClassification());
        assertEquals(MicrosoftAccountClassifier.DELIVERY_UNKNOWN, c.organizerInviteDelivery());
    }

    @Test
    void blankEmail_isUnknown() {
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_UNKNOWN,
                MicrosoftAccountClassifier.classifyByEmail("   ").accountClassification());
    }

    @Test
    void malformedEmail_isUnknown() {
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_UNKNOWN,
                MicrosoftAccountClassifier.classifyByEmail("not-an-email").accountClassification());
    }

    @Test
    void mixedCase_normalizedBeforeMatch() {
        // Domain comparison must be case-insensitive
        assertEquals(MicrosoftAccountClassifier.ACCOUNT_PERSONAL_MSA,
                MicrosoftAccountClassifier.classifyByEmail("user@OUTLOOK.COM").accountClassification());
    }
}
