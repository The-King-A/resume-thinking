package com.resumethinking.platform.ids;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReadableIdGeneratorTest {
    @Test
    void generatesIndependentReadableSequencesWithPadding() {
        ReadableIdGenerator generator = new InMemoryReadableIdGenerator();

        assertThat(generator.next(BusinessIdType.USER)).isEqualTo("user001");
        assertThat(generator.next(BusinessIdType.USER)).isEqualTo("user002");
        assertThat(generator.next(BusinessIdType.PROFILE)).isEqualTo("profile001");

        for (int i = 0; i < 997; i++) {
            generator.next(BusinessIdType.USER);
        }
        assertThat(generator.next(BusinessIdType.USER)).isEqualTo("user1000");
    }

    @Test
    void rejectsInvalidBusinessIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> ReadableIdGenerator.validate(BusinessIdType.USER, "profile001"));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadableIdGenerator.validate(BusinessIdType.USER, "user01"));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadableIdGenerator.validate(BusinessIdType.USER, ""));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadableIdGenerator.validate(BusinessIdType.USER, "user" + "1".repeat(61)));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadableIdGenerator.format(BusinessIdType.USER, 0));
    }

    @Test
    void concurrentCallsRemainUnique() throws Exception {
        ReadableIdGenerator generator = new InMemoryReadableIdGenerator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                calls.add(() -> generator.next(BusinessIdType.RESULT));
            }
            List<String> ids = new ArrayList<>();
            for (var future : pool.invokeAll(calls)) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(800).doesNotHaveDuplicates();
            assertThat(ids).allMatch(id -> id.startsWith("result"));
        } finally {
            pool.shutdownNow();
        }
    }
}
