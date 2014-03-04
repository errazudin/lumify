package com.altamiracorp.lumify.web.routes.entity;

import com.altamiracorp.bigtable.model.ModelSession;
import com.altamiracorp.bigtable.model.user.ModelUserContext;
import com.altamiracorp.lumify.core.config.Configuration;
import com.altamiracorp.lumify.core.model.audit.AuditAction;
import com.altamiracorp.lumify.core.model.audit.AuditRepository;
import com.altamiracorp.lumify.core.model.detectedObjects.DetectedObjectModel;
import com.altamiracorp.lumify.core.model.detectedObjects.DetectedObjectRepository;
import com.altamiracorp.lumify.core.model.detectedObjects.DetectedObjectRowKey;
import com.altamiracorp.lumify.core.model.ontology.OntologyRepository;
import com.altamiracorp.lumify.core.model.user.UserRepository;
import com.altamiracorp.lumify.core.security.VisibilityTranslator;
import com.altamiracorp.lumify.core.user.User;
import com.altamiracorp.lumify.core.user.UserProvider;
import com.altamiracorp.lumify.core.util.GraphUtil;
import com.altamiracorp.lumify.web.BaseRequestHandler;
import com.altamiracorp.miniweb.HandlerChain;
import com.altamiracorp.securegraph.*;
import com.altamiracorp.securegraph.util.IterableUtils;
import com.google.inject.Inject;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Iterator;

public class UnresolveDetectedObject extends BaseRequestHandler {
    private final Graph graph;
    private final AuditRepository auditRepository;
    private final DetectedObjectRepository detectedObjectRepository;
    private final ModelSession modelSession;
    private final VisibilityTranslator visibilityTranslator;
    private final UserProvider userProvider;
    private final OntologyRepository ontologyRepository;

    @Inject
    public UnresolveDetectedObject(
            final Graph graph,
            final AuditRepository auditRepository,
            final UserRepository userRepository,
            final DetectedObjectRepository detectedObjectRepository,
            final ModelSession modelSession,
            final VisibilityTranslator visibilityTranslator,
            final Configuration configuration,
            final UserProvider userProvider,
            final OntologyRepository ontologyRepository) {
        super(userRepository, configuration);
        this.graph = graph;
        this.auditRepository = auditRepository;
        this.detectedObjectRepository = detectedObjectRepository;
        this.modelSession = modelSession;
        this.visibilityTranslator = visibilityTranslator;
        this.userProvider = userProvider;
        this.ontologyRepository = ontologyRepository;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        final String rowKey = getRequiredParameter(request, "rowKey");
        final String artifactId = getRequiredParameter(request, "artifactId");
        final String visibilitySource = getRequiredParameter(request, "visibilitySource");
        String workspaceId = getWorkspaceId(request);
        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        ModelUserContext modelUserContext = userProvider.getModelUserContext(user, getWorkspaceId(request));

        DetectedObjectRowKey detectedObjectRowKey = new DetectedObjectRowKey(rowKey);
        DetectedObjectModel detectedObjectModel = detectedObjectRepository.findByRowKey(rowKey, modelUserContext);
        Object resolvedId = detectedObjectModel.getMetadata().getResolvedId();

        Vertex artifactVertex = graph.getVertex(artifactId, authorizations);
        Vertex resolvedVertex = graph.getVertex(resolvedId, authorizations);

        JSONObject result = new JSONObject();
        String columnFamilyName = detectedObjectModel.getMetadata().getColumnFamilyName();
        String columnName = detectedObjectModel.getMetadata().RESOLVED_ID;

        if (detectedObjectModel.getMetadata().getProcess() == null) {
            modelSession.deleteRow(detectedObjectModel.getTableName(), detectedObjectRowKey, user.getModelUserContext());
            result.put("deleteTag", true);
        } else {
            detectedObjectModel.get(columnFamilyName).getColumn(columnName).setDirty(true);
            modelSession.deleteColumn(detectedObjectModel, detectedObjectModel.getTableName(), columnFamilyName, columnName, user.getModelUserContext());
            result = detectedObjectModel.toJson();
        }

        Iterable<Object> edgeIds = artifactVertex.getEdgeIds(resolvedVertex, Direction.BOTH, authorizations);
        if (IterableUtils.count(edgeIds) == 1) {
            Edge edge = graph.getEdge(edgeIds.iterator().next(), authorizations);
            graph.removeEdge(edge, authorizations);
            String label = ontologyRepository.getDisplayNameForLabel(edge.getLabel());

            JSONObject visibilityJson = GraphUtil.updateVisibilityJson(null, visibilitySource, workspaceId);
            Visibility visibility = visibilityTranslator.toVisibility(visibilityJson);
            auditRepository.auditRelationship(AuditAction.DELETE, artifactVertex, resolvedVertex, label, "", "", user, visibility);

            result.put("deleteEdge", true);
            result.put("edgeId", edge.getId());
            graph.flush();
        }

        JSONObject artifactJson = GraphUtil.toJson(artifactVertex);
        Iterator<DetectedObjectModel> detectedObjectModels = detectedObjectRepository.findByGraphVertexId(artifactId.toString(), modelUserContext).iterator();
        JSONArray detectedObjects = new JSONArray();
        while (detectedObjectModels.hasNext()) {
            DetectedObjectModel model = detectedObjectModels.next();
            JSONObject detectedObjectModelJson = model.toJson();
            if (model.getMetadata().getResolvedId() != null) {
                detectedObjectModelJson.put("entityVertex", GraphUtil.toJson(graph.getVertex(model.getMetadata().getResolvedId(), authorizations)));
            }
            detectedObjects.put(detectedObjectModelJson);
        }
        artifactJson.put("detectedObjects", detectedObjects);
        result.put("artifactVertex", artifactJson);

        respondWithJson(response, result);
    }
}
