package spring;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import io.mats3.examples.MatsExampleKit;
import io.mats3.test.MatsTestHelp;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Exercises the {@link spring.SpringMediumService}. Note: This is most definitely not how to use a MatsFuturizer in
 * production! This is only to demonstrate a single call from a main-class.
 */
public class SpringMediumServiceMainCall {
    public static void main(String... args) throws Exception {
        MatsExampleKit.configureLogbackToConsole_Warn();

        // NOTE: NEVER do this in production! MatsFuturizer is a singleton, long-lived service!
        try (MatsFuturizer matsFuturizer = MatsExampleKit.createMatsFuturizer()) {
            // ----- A single call
            double random = ThreadLocalRandom.current().nextDouble(-10, 10);
            CompletableFuture<Reply<SpringMediumServiceReplyDto>> future = matsFuturizer.futurizeNonessential(
                    MatsTestHelp.traceId(), "SpringMediumServiceMainCall", "SpringMediumService.matsClassMapping",
                    SpringMediumServiceReplyDto.class,
                    new SpringMediumServiceRequestDto(Math.PI, Math.E, random));

            // :: Receive, verify and print.
            SpringMediumServiceReplyDto reply = future.get().getReply();
            boolean correct = Math.pow(Math.PI * Math.E, random) == reply.result;
            System.out.println("######## Got reply! " + reply + " - " + (correct ? "Correct!" : "Wrong!"));
        }
    }

    // ----- Contract copied from SimpleService

    record SpringMediumServiceRequestDto(double multiplicand, double multiplier, double exponent) {
    }

    record SpringMediumServiceReplyDto(double result) {
    }
}