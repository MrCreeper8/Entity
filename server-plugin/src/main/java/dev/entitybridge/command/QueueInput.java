package dev.entitybridge.command;

import com.google.gson.JsonObject;
import dev.entitybridge.mission.OrderedJobQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Validates the whole typed chain before its caller may modify either journal. */
final class QueueInput {
    private QueueInput() { }

    static List<OrderedJobQueue.Step> parse(String[] input, String requester, int maxAmount,
                                           Function<String, String> itemNormalizer,
                                           Function<String, String> playerNormalizer,
                                           Function<CommandInput.ResourceQuery, JsonObject> blockArguments) {
        int start = input.length > 1 && input[1].equalsIgnoreCase("add") ? 2 : 1;
        String chain = String.join(" ", java.util.Arrays.copyOfRange(input, start, input.length));
        String[] segments = chain.split(";", -1);
        ArrayList<OrderedJobQueue.Step> steps = new ArrayList<>();
        for (String segment : segments) {
            String label = segment.trim();
            if (label.isBlank()) throw new IllegalArgumentException("Empty queue step");
            String[] args = label.split("\\s+");
            String action = CommandInput.canonicalAction(args[0]);
            String operation = null;
            JsonObject payload = new JsonObject();
            switch (action) {
                case "come" -> {
                    if (args.length != 1) throw new IllegalArgumentException("Use come without arguments");
                    // Empty come is intentional: the runtime defaults its target to requestedBy.
                }
                case "get" -> {
                    List<CommandInput.ItemAmount> batch = CommandInput.itemBatch(args, 1, maxAmount, itemNormalizer);
                    if (batch != null) payload = ItemBatchArguments.encode(batch, null);
                    else {
                        CommandInput.ResourceQuery query = CommandInput.resource(args, 1, maxAmount);
                        String item = itemNormalizer.apply(query.query());
                        if (item == null) throw new IllegalArgumentException("Unknown obtainable item: " + query.query());
                        payload.addProperty("item", item); payload.addProperty("count", query.amount());
                        payload.addProperty("query", item.replace("minecraft:", ""));
                        payload.addProperty("amount", query.amount());
                    }
                }
                case "bring", "give" -> {
                    CommandInput.DeliveryBatch batch = CommandInput.deliveryBatch(args, 1, maxAmount,
                            requester, itemNormalizer, playerNormalizer);
                    if (batch != null) {
                        payload = ItemBatchArguments.encode(batch.items(), batch.player());
                        payload.addProperty("useReserves", batch.useReserves());
                    } else {
                        CommandInput.DeliveryQuery query = CommandInput.delivery(args, 1, maxAmount,
                                requester, itemNormalizer, playerNormalizer);
                        if (query.all() && !action.equals("give")) throw new IllegalArgumentException("Use all only with give");
                        payload.addProperty("item", query.item()); payload.addProperty("count", query.count());
                        payload.addProperty("player", query.player()); payload.addProperty("useReserves", query.useReserves());
                        if (query.all()) payload.addProperty("all", true);
                    }
                }
                case "mine" -> payload = blockArguments.apply(CommandInput.resource(args, 1, maxAmount));
                case "gear" -> {
                    CommandInput.GearRequest request = CommandInput.gear(args);
                    if (request.operation().equals("status")) throw new IllegalArgumentException("gear status is a query, not a job");
                    payload.addProperty("tier", request.tier());
                }
                case "goto" -> {
                    CommandInput.GoToRequest request = CommandInput.goTo(args);
                    if (request.player() != null) {
                        action = "come";
                        String player = playerNormalizer.apply(request.player());
                        if (player == null) throw new IllegalArgumentException("Invalid player target");
                        payload.addProperty("player", player);
                    } else {
                        payload.addProperty("x", request.x()); payload.addProperty("y", request.y()); payload.addProperty("z", request.z());
                    }
                }
                case "home", "stock" -> {
                    String expected = action.equals("home") ? "go" : "run";
                    if (args.length != 2 || !args[1].equalsIgnoreCase(expected))
                        throw new IllegalArgumentException("Only " + action + " " + expected + " is a finite queue job");
                    operation = "policy." + action;
                    payload.addProperty("operation", expected);
                }
                default -> throw new IllegalArgumentException("Unsupported queue step '" + action
                        + "'. Supported: get, give, bring, mine, gear, come, goto, home go, stock run");
            }
            steps.add(new OrderedJobQueue.Step(action, operation, payload, requester, label));
        }
        if (steps.size() > OrderedJobQueue.MAX_STEPS) throw new IllegalArgumentException("Too many queue steps");
        return List.copyOf(steps);
    }
}
