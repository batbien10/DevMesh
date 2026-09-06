package devmesh.tui.tea;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;


public sealed interface Command
        permits Command.Simple, Command.Tick, Command.CheckWindowSize, Command.Batch, Command.PrintLine {


    static Command of(Supplier<Message> fn) {
        return new Simple(fn);
    }


    static Command tick(Duration delay, Function<Instant, Message> fn) {
        return new Tick(delay, fn);
    }


    static Command checkWindowSize() {
        return new CheckWindowSize();
    }


    static Command batch(Command... cmds) {
        return new Batch(List.of(cmds));
    }


    static Command println(String text) {
        return new PrintLine(text);
    }

    record Simple(Supplier<Message> fn) implements Command {}
    record Tick(Duration delay, Function<Instant, Message> fn) implements Command {}
    record CheckWindowSize() implements Command {}
    record Batch(List<Command> commands) implements Command {}
    record PrintLine(String text) implements Command {}
}
