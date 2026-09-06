package devmesh.tui.tea;

import java.util.function.Supplier;


public record UpdateResult<M extends Model>(M model, Command command) {

    public static <M extends Model> UpdateResult<M> from(M model) {
        return new UpdateResult<>(model, null);
    }

    public static <M extends Model> UpdateResult<M> from(M model, Command cmd) {
        return new UpdateResult<>(model, cmd);
    }


    public static <M extends Model> UpdateResult<M> from(M model, Supplier<Message> fn) {
        return new UpdateResult<>(model, Command.of(fn));
    }
}
