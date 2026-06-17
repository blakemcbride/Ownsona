package ai.ownsona.memory;

import ai.ownsona.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.database.Connection;
import org.kissweb.restServer.MainServlet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only multi-hop traversal over the relation graph (Tier 4 phase 2).
 * No LLM at query time --- pure breadth-first search over
 * {@code memory_relations}, so it is safe on the synchronous request path.
 *
 * <p>Edges are treated as undirected for reachability: from a seed entity,
 * each hop collects every relation whose subject or object (case-insensitive)
 * is in the current frontier, then advances to the not-yet-visited
 * endpoints.  Soft-deleted source memories are excluded (handled in
 * {@link RelationRepository}); results carry each edge's current source text.
 */
public final class RelationService {

    private static final Logger logger = LogManager.getLogger(RelationService.class);

    /** Hard cap on hops regardless of request / config. */
    static final int MAX_HOPS_CAP = 5;

    private final RelationRepository relRepo;
    private final String userId;

    public RelationService(RelationRepository relRepo) {
        this.relRepo = relRepo;
        this.userId  = Config.OWNSONA_USER_ID;
    }

    /**
     * Traverse outward from {@code entity} up to {@code maxHops} and return
     * the relations reached (deduplicated, capped at
     * {@link Config#GRAPH_MAX_RELATIONS}).
     */
    public List<MemoryRelation> queryRelations(String entity, Integer maxHops) {
        final String seed = (entity == null) ? null : entity.trim();
        if (seed == null || seed.isEmpty())
            throw new ServiceException(ServiceException.INVALID_INPUT, "entity is required.");
        final int hops = clampHops(maxHops);
        final int cap = Config.GRAPH_MAX_RELATIONS;

        final Connection db = MainServlet.openNewConnection();
        boolean success = false;
        try {
            final Map<Long, MemoryRelation> collected = new LinkedHashMap<>();
            final Set<String> visited = new HashSet<>();
            List<String> frontier = new ArrayList<>();
            final String seedLower = seed.toLowerCase();
            visited.add(seedLower);
            frontier.add(seedLower);

            for (int hop = 0; hop < hops && !frontier.isEmpty() && collected.size() < cap; hop++) {
                final List<MemoryRelation> rels = relRepo.findRelationsTouching(db, userId, frontier, cap);
                final List<String> next = new ArrayList<>();
                for (MemoryRelation r : rels) {
                    if (collected.size() >= cap)
                        break;
                    collected.putIfAbsent(r.id, r);
                    addEndpoint(visited, next, r.subject);
                    addEndpoint(visited, next, r.object);
                }
                frontier = next;
            }

            logger.info("query_relations: entity_chars={} hops={} relations={}",
                    seed.length(), hops, collected.size());
            success = true;
            return new ArrayList<>(collected.values());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ServiceException.DATABASE_ERROR,
                    "query_relations failed: " + e.getMessage(), e);
        } finally {
            MainServlet.closeConnection(db, success);
        }
    }

    private static void addEndpoint(Set<String> visited, List<String> next, String entity) {
        if (entity == null)
            return;
        final String lower = entity.trim().toLowerCase();
        if (lower.isEmpty())
            return;
        if (visited.add(lower))
            next.add(lower);
    }

    /** Package-private for unit tests. */
    static int clampHops(Integer requested) {
        if (requested == null)
            return Math.min(Config.GRAPH_MAX_HOPS, MAX_HOPS_CAP);
        if (requested < 1)
            throw new ServiceException(ServiceException.INVALID_INPUT, "max_hops must be >= 1.");
        return Math.min(requested, MAX_HOPS_CAP);
    }
}
