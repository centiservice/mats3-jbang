//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS io.mats3.examples:mats-jbangkit:1.0.0

package simple;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.examples.jbang.MatsJbangKit;
import io.mats3.test.MatsTestHelp;

/**
 * A pretty bad example of how a Request with ReplyTo(Terminator) works! It is bad as this is not a proper long-running
 * service (it terminates when receiving a special stopReceiving message). It is also bad because cannot have more than
 * one instance of it running, as it relies on all replies to be processed on this single instance that initiated the
 * flows - otherwise, another node might get the stopReceiving message. Rather, a proper service would probably store
 * some state in a service-shared database upon reception on the Terminator, thus not relying on what specific instance
 * receives it.
 * <p>
 * Requires an ActiveMQ running on localhost, and at least one instance of {@link SimpleService} and/or
 * {@link spring.SpringSimpleService} running.
 * <p>
 * You may invoke it with "-Ddrain" to let the receiver linger 10 seconds more to drain the queue if it has ended up
 * with multiple sets of messages, for example if you invoked it multiple times while no process was consuming messages
 * from 'SimpleService.simple'.
 */
public class SimpleServiceMainTerminator {

    private static final Logger log = MatsJbangKit.getClassLogger();

    private static final class State {
        boolean stopReceiver;
    }

    private static final String TERMINATOR = "SimpleServiceMainTerminator.private.terminator";

    public static void main(String... args) throws Exception {
        // :: Get hold of MatsFactory
        MatsFactory matsFactory = MatsJbangKit.createMatsFactory();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();

        // :: Create a Terminator, which notifies main-thread to stop if it receives a message with a special state.
        // Note that this is definitely not how a long-running service is supposed to work: This main-class relies on
        // there only being one instance of it running, as otherwise the other instance might get the stop-message!
        // In a proper service, the Terminator would probably store some value in a service-shared database, thus not
        // relying on which node of the service receives which message.
        createTerminator(matsFactory, latch, counter);

        // :: Initiate a bunch of Request messages to the SimpleService, with ReplyTo set to the above Terminator.
        // Lastly, a message with state "stopReceiver=true" is added, which the Terminator sees and counts down the
        // latch, letting the main thread (this thread) continue. All these are sent in a single transaction, but
        // will be processed by the running (Spring)SimpleService instances transactionally one by one. Since sequence
        // order is specifically not guaranteed with Mats3, the 'stopReceiver=true'-message might "overtake" some of the
        // other messages, stopping the receiver before all messages have been processed. If this happens, you should
        // see the "left over" messages on the MatsBrokerMonitor. If you start up this main-class again, you'd then get
        // those queued messages that wasn't processed on the previous incarnation. If you want to drain the queue,
        // even though there are multiple 'stopReceiver=true'-messages (e.g. you've started this multiple times with
        // no 'SimpleService.simple' running), invoke it with "-Ddrain", and it'll receive for 10 seconds more.
        MatsInitiator initiator = matsFactory.getDefaultInitiator();
        initiator.initiate(init -> {
            for (int i = 0; i < 500; i++) {
                init.traceId(MatsTestHelp.traceId())
                        .from("SimpleServiceMainTerminator.initMultiple")
                        .to("SimpleService.simple")
                        .replyTo(TERMINATOR, new State())
                        .request(new SimpleServiceRequestDto(2, "two"));
            }
            State state = new State();
            state.stopReceiver = true;
            init.traceId(MatsTestHelp.traceId())
                    .from("SimpleServiceMainTerminator.stopReceiver")
                    .to("SimpleService.simple")
                    .replyTo(TERMINATOR, state)
                    .request(new SimpleServiceRequestDto(2, "two"));
        });

        boolean await = latch.await(30, TimeUnit.SECONDS);
        if (await) {
            log.info("Got pinged by Terminator that it has received the stopReceiver message.");
        }
        else {
            log.error("Didn't get the stopReceiver message: Timeout (some other concurrently running node got it?).");
        }

        // ?: Have the jbang file been invoked with "-Ddrain"?
        if (System.getProperty("drain") != null) {
            log.info("Sleeping 10 seconds to drain the queue.");
            Thread.sleep(10_000);
        }

        log.info("Number of messages received: " + counter);

        // :: Clean up to exit.
        matsFactory.close();
    }

    private static void createTerminator(MatsFactory matsFactory, CountDownLatch latch, AtomicInteger counter) {
        matsFactory.terminator(TERMINATOR, State.class, SimpleServiceReplyDto.class, (ctx, state, msg) -> {
            log.info("Got a message from " + ctx.getFromStageId() + "@" + ctx.getFromAppName() + ": " + msg);
            counter.incrementAndGet(); // Such JVM statefulness is NOT proper Mats3!! Demonstration purpose only!
            if (state.stopReceiver) {
                log.info("### Got stopReceiver message!");
                latch.countDown();
            }
        });
    }

    // ----- Contract copied from SimpleService

    record SimpleServiceRequestDto(int number, String string) {
    }

    record SimpleServiceReplyDto(String result, int numChars) {
    }
}
