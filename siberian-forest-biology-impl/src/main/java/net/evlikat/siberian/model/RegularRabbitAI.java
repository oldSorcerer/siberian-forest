package net.evlikat.siberian.model;

import net.evlikat.siberian.geo.Direction;
import net.evlikat.siberian.geo.Position;
import net.evlikat.siberian.utils.CollectionUtils;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.evlikat.siberian.model.RegularRabbitTargetAttitude.COMPETITOR;
import static net.evlikat.siberian.model.RegularRabbitTargetAttitude.FOOD;
import static net.evlikat.siberian.model.RegularRabbitTargetAttitude.MATE;
import static net.evlikat.siberian.model.RegularRabbitTargetAttitude.PREDATOR;
import static net.evlikat.siberian.utils.CollectionUtils.best;

public class RegularRabbitAI implements AI<RabbitInfo> {

    private static final Map<RegularRabbitTargetAttitude, Integer> ADULT_VALUE_MAP
        = new EnumMap<>(RegularRabbitTargetAttitude.class);

    private static final Map<RegularRabbitTargetAttitude, Integer> BABY_VALUE_MAP
        = new EnumMap<>(RegularRabbitTargetAttitude.class);

    static {
        ADULT_VALUE_MAP.put(RegularRabbitTargetAttitude.PREDATOR, -50);
        ADULT_VALUE_MAP.put(RegularRabbitTargetAttitude.COMPETITOR, -5);
        ADULT_VALUE_MAP.put(RegularRabbitTargetAttitude.MATE, 10);
        ADULT_VALUE_MAP.put(RegularRabbitTargetAttitude.FOOD, 30);

        BABY_VALUE_MAP.put(RegularRabbitTargetAttitude.PREDATOR, -50);
        BABY_VALUE_MAP.put(RegularRabbitTargetAttitude.COMPETITOR, 0);
        BABY_VALUE_MAP.put(RegularRabbitTargetAttitude.MATE, 0);
        BABY_VALUE_MAP.put(RegularRabbitTargetAttitude.FOOD, 30);
    }

    private boolean wantsToEat(RabbitInfo me) {
        return me.health().part() < 0.5d;
    }

    @Override
    public Map<Position, Integer> evaluate(RabbitInfo me, Visibility visibility) {
        Map<RegularRabbitTargetAttitude, Integer> valueMap = me.adult() ? ADULT_VALUE_MAP : BABY_VALUE_MAP;

        Map<Position, Set<RegularRabbitTargetAttitude>> positionValues = updateWithUnits(me, visibility.units());
        HashMap<Position, Integer> positionValueMap = positionValues.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().stream().mapToInt(valueMap::get).sum(),
                Integer::sum, HashMap::new));

        Map<Position, Integer> predatorValueMap = updateProliferatingValues(visibility, PREDATOR, positionValues);
        Map<Position, Integer> competitorValueMap = updateProliferatingValues(visibility, COMPETITOR, positionValues);

        Integer foodCellValue = valueMap.get(FOOD);
        Map<Position, Integer> cellValues = visibility.cells()
            .collect(Collectors.toMap(
                Cell::getPosition,
                c -> c.getGrass().getFoodCurrent() >= c.getGrass().getFoodValue() ? foodCellValue : 0));

        return CollectionUtils.mergeMaps(
            Integer::sum, predatorValueMap, competitorValueMap, positionValueMap, cellValues);
    }

    @Override
    public Optional<Position> move(RabbitInfo me, Visibility visibility) {
        List<Position> positions = bestPositions(me, visibility);
        return positions.stream().findFirst()
            .map(bestPos -> {
                List<Direction> availableDirections = Direction.shuffledValues()
                    .filter(dir -> !me.getPosition().by(dir).adjustableIn(0, 0, visibility.getWidth(), visibility.getHeight()))
                    .collect(Collectors.toList());
                return me.getPosition().inDirectionTo(bestPos, availableDirections);
            });
    }

    private List<Position> bestPositions(RabbitInfo me, Visibility visibility) {
        Map<Position, Integer> totalValueMap = evaluate(me, visibility);
        List<Map.Entry<Position, Integer>> res = best(totalValueMap);
        return res.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private Map<Position, Integer> updateProliferatingValues(Visibility visibility, RegularRabbitTargetAttitude key,
                                                             Map<Position, Set<RegularRabbitTargetAttitude>> positionValues) {
        List<Position> negativePositions = positionValues.entrySet().stream()
            .filter(e -> e.getValue().contains(key))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        Integer epicenterValue = ADULT_VALUE_MAP.get(key);

        return negativePositions.stream()
            .map(position ->
                (Map<Position, Integer>) visibility.cells()
                    .map(Cell::getPosition)
                    .collect(Collectors.toMap(
                        Function.identity(),
                        pos -> epicenterValue + position.distance(pos),
                        Integer::sum, HashMap::new)))
            .reduce((map1, map2) -> CollectionUtils.mergeMaps(Integer::sum, map1, map2)).orElse(Collections.emptyMap());
    }

    private Map<Position, Set<RegularRabbitTargetAttitude>> updateWithUnits(
        RabbitInfo me,
        Stream<? extends LivingUnitInfo> units
    ) {
        Map<Position, Set<RegularRabbitTargetAttitude>> positionValues = new HashMap<>();
        Map<Position, List<LivingUnitInfo>> positionUnits = units.collect(Collectors.groupingBy(LivingUnitInfo::getPosition));

        positionUnits.forEach((key, value) -> value.stream()
            .map(InterestUnit::new)
            .forEach(iu -> {
                if (iu.asPredator != null) {
                    positionValues.computeIfAbsent(key, (pos) -> new HashSet<>()).add(PREDATOR);
                }
                if (iu.asMate != null) {
                    RegularRabbitTargetAttitude attitude = goodPartner(me, iu.asMate) ? MATE : COMPETITOR;
                    positionValues.computeIfAbsent(key, (pos) -> new HashSet<>()).add(attitude);
                }
            }));

        return positionValues;
    }

    private boolean goodPartner(RabbitInfo me, Rabbit candidate) {
        return candidate.adult()
            && candidate.sex != me.sex()
            && !candidate.pregnancy().isPresent()
            && !me.pregnancy().isPresent();
    }

    @Override
    public Optional<Food> feed(RabbitInfo me, Visibility visibility) {
        if (!wantsToEat(me)) {
            return Optional.empty();
        }
        return visibility.cells()
            .filter(c -> c.getPosition().equals(me.getPosition()))
            .findAny()
            .map(Cell::getGrass);
    }

    private static class InterestUnit {

        final LivingUnitInfo asUnit;
        final Rabbit asMate;
        final Wolf asPredator;

        InterestUnit(LivingUnitInfo unit) {
            this.asUnit = unit;
            if (unit instanceof Rabbit) {
                this.asMate = (Rabbit) unit;
            } else {
                this.asMate = null;
            }
            if (unit instanceof Wolf) {
                this.asPredator = (Wolf) unit;
            } else {
                this.asPredator = null;
            }
        }
    }
}