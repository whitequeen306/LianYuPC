package com.lianyu.service.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码校验规则：≥6 位、必须同时包含字母和数字（不再强制大小写/8 位）。
 */
class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private static boolean passwordOk(String pwd) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("tester01");
        req.setPassword(pwd);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validateProperty(req, "password");
        return violations.isEmpty();
    }

    @Test
    void acceptsSixCharsWithLetterAndDigit() {
        assertTrue(passwordOk("abc123"));
    }

    @Test
    void acceptsMixedCaseWithDigit() {
        assertTrue(passwordOk("Abc1234"));
    }

    @Test
    void acceptsLongerAlnum() {
        assertTrue(passwordOk("lianyu2026"));
    }

    @Test
    void rejectsLettersOnly() {
        assertTrue(!passwordOk("abcdef"));
    }

    @Test
    void rejectsDigitsOnly() {
        assertTrue(!passwordOk("123456"));
    }

    @Test
    void rejectsTooShort() {
        assertTrue(!passwordOk("ab1"));
    }

    @Test
    void rejectsFiveCharsEvenIfAlnum() {
        assertTrue(!passwordOk("aB1x9"));
    }

    @Test
    void rejectsBlank() {
        assertTrue(!passwordOk(""));
    }
}
