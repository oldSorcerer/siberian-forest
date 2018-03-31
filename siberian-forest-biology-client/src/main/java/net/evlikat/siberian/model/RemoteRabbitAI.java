package net.evlikat.siberian.model;

import net.evlikat.siberian.geo.Position;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class RemoteRabbitAI implements AI<RabbitInfo> {

    @Override
    public List<Position> aim(RabbitInfo unit, Visibility visibility) {
        return Collections.emptyList();
    }

    @Override
    public Optional<Position> move(RabbitInfo unit, Visibility visibility) {
        return Optional.empty();
    }

    @Override
    public Optional<Food> feed(RabbitInfo unit, Visibility visibility) {
        return Optional.empty();
    }
}
