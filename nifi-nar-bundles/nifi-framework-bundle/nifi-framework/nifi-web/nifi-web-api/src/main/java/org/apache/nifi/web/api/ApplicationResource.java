/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.api;

import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.api.representation.Form;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.sun.jersey.server.impl.model.method.dispatch.FormDispatchProvider;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.authorization.user.NiFiUserUtils;
import org.apache.nifi.cluster.coordination.ClusterCoordinator;
import org.apache.nifi.cluster.coordination.http.replication.RequestReplicator;
import org.apache.nifi.cluster.manager.NodeResponse;
import org.apache.nifi.cluster.manager.exception.NoClusterCoordinatorException;
import org.apache.nifi.cluster.manager.exception.UnknownNodeException;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.controller.Snippet;
import org.apache.nifi.remote.HttpRemoteSiteListener;
import org.apache.nifi.remote.VersionNegotiator;
import org.apache.nifi.remote.exception.BadRequestException;
import org.apache.nifi.remote.exception.HandshakeException;
import org.apache.nifi.remote.exception.NotAuthorizedException;
import org.apache.nifi.remote.protocol.ResponseCode;
import org.apache.nifi.remote.protocol.http.HttpHeaders;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.AuthorizableLookup;
import org.apache.nifi.web.AuthorizeAccess;
import org.apache.nifi.web.NiFiServiceFacade;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.api.dto.RevisionDTO;
import org.apache.nifi.web.api.dto.SnippetDTO;
import org.apache.nifi.web.api.entity.ComponentEntity;
import org.apache.nifi.web.api.entity.TransactionResultEntity;
import org.apache.nifi.web.api.request.ClientIdParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.nifi.remote.protocol.http.HttpHeaders.LOCATION_URI_INTENT_NAME;
import static org.apache.nifi.remote.protocol.http.HttpHeaders.LOCATION_URI_INTENT_VALUE;

/**
 * Base class for controllers.
 */
public abstract class ApplicationResource {

    public static final String VERSION = "version";
    public static final String CLIENT_ID = "clientId";
    public static final String PROXY_SCHEME_HTTP_HEADER = "X-ProxyScheme";
    public static final String PROXY_HOST_HTTP_HEADER = "X-ProxyHost";
    public static final String PROXY_PORT_HTTP_HEADER = "X-ProxyPort";
    public static final String PROXY_CONTEXT_PATH_HTTP_HEADER = "X-ProxyContextPath";

    private static final Logger logger = LoggerFactory.getLogger(ApplicationResource.class);

    public static final String NODEWISE = "false";

    @Context
    private HttpServletRequest httpServletRequest;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpContext httpContext;

    protected NiFiProperties properties;
    private RequestReplicator requestReplicator;
    private ClusterCoordinator clusterCoordinator;


    /**
     * Generate a resource uri based off of the specified parameters.
     *
     * @param path path
     * @return resource uri
     */
    protected String generateResourceUri(final String... path) {
        final UriBuilder uriBuilder = uriInfo.getBaseUriBuilder();
        uriBuilder.segment(path);
        URI uri = uriBuilder.build();
        try {

            // check for proxy settings
            final String scheme = httpServletRequest.getHeader(PROXY_SCHEME_HTTP_HEADER);
            final String host = httpServletRequest.getHeader(PROXY_HOST_HTTP_HEADER);
            final String port = httpServletRequest.getHeader(PROXY_PORT_HTTP_HEADER);
            String baseContextPath = httpServletRequest.getHeader(PROXY_CONTEXT_PATH_HTTP_HEADER);

            // if necessary, prepend the context path
            String resourcePath = uri.getPath();
            if (baseContextPath != null) {
                // normalize context path
                if (!baseContextPath.startsWith("/")) {
                    baseContextPath = "/" + baseContextPath;
                }

                // determine the complete resource path
                resourcePath = baseContextPath + resourcePath;
            }

            // determine the port uri
            int uriPort = uri.getPort();
            if (port != null) {
                if (StringUtils.isWhitespace(port)) {
                    uriPort = -1;
                } else {
                    try {
                        uriPort = Integer.parseInt(port);
                    } catch (final NumberFormatException nfe) {
                        logger.warn(String.format("Unable to parse proxy port HTTP header '%s'. Using port from request URI '%s'.", port, uriPort));
                    }
                }
            }

            // construct the URI
            uri = new URI(
                    (StringUtils.isBlank(scheme)) ? uri.getScheme() : scheme,
                    uri.getUserInfo(),
                    (StringUtils.isBlank(host)) ? uri.getHost() : host,
                    uriPort,
                    resourcePath,
                    uri.getQuery(),
                    uri.getFragment());

        } catch (final URISyntaxException use) {
            throw new UriBuilderException(use);
        }
        return uri.toString();
    }

    /**
     * Edit the response headers to indicating no caching.
     *
     * @param response response
     * @return builder
     */
    protected ResponseBuilder noCache(final ResponseBuilder response) {
        final CacheControl cacheControl = new CacheControl();
        cacheControl.setPrivate(true);
        cacheControl.setNoCache(true);
        cacheControl.setNoStore(true);
        return response.cacheControl(cacheControl);
    }

    /**
     * If the application is operating as a node, then this method adds the cluster context information to the response using the response header 'X-CLUSTER_CONTEXT'.
     *
     * @param response response
     * @return builder
     */
    protected ResponseBuilder clusterContext(final ResponseBuilder response) {
        // TODO: Remove this method. Since ClusterContext was removed, it is no longer needed. However,
        // it is called by practically every endpoint so for now it is just being stubbed out.
        return response;
    }

    protected String generateUuid() {
        final Optional<String> seed = getIdGenerationSeed();
        return seed.isPresent() ? UUID.nameUUIDFromBytes(seed.get().getBytes(StandardCharsets.UTF_8)).toString() : UUID.randomUUID().toString();
    }

    protected Optional<String> getIdGenerationSeed() {
        final String idGenerationSeed = httpServletRequest.getHeader(RequestReplicator.CLUSTER_ID_GENERATION_SEED_HEADER);
        if (StringUtils.isBlank(idGenerationSeed)) {
            return Optional.empty();
        }

        return Optional.of(idGenerationSeed);
    }


    /**
     * Generates an Ok response with no content.
     *
     * @return an Ok response with no content
     */
    protected ResponseBuilder generateOkResponse() {
        return noCache(Response.ok());
    }

    /**
     * Generates an Ok response with the specified content.
     *
     * @param entity The entity
     * @return The response to be built
     */
    protected ResponseBuilder generateOkResponse(final Object entity) {
        final ResponseBuilder response = Response.ok(entity);
        return noCache(response);
    }

    /**
     * Generates a 201 Created response with the specified content.
     *
     * @param uri    The URI
     * @param entity entity
     * @return The response to be built
     */
    protected ResponseBuilder generateCreatedResponse(final URI uri, final Object entity) {
        // generate the response builder
        return Response.created(uri).entity(entity);
    }

    /**
     * Generates a 401 Not Authorized response with no content.
     *
     * @return The response to be built
     */
    protected ResponseBuilder generateNotAuthorizedResponse() {
        // generate the response builder
        return Response.status(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * Generates a 150 Node Continue response to be used within the cluster request handshake.
     *
     * @return a 150 Node Continue response to be used within the cluster request handshake
     */
    protected ResponseBuilder generateContinueResponse() {
        return Response.status(RequestReplicator.NODE_CONTINUE_STATUS_CODE);
    }

    protected URI getAbsolutePath() {
        return uriInfo.getAbsolutePath();
    }

    protected MultivaluedMap<String, String> getRequestParameters() {
        final MultivaluedMap<String, String> entity = new MultivaluedMapImpl();

        // get the form that jersey processed and use it if it exists (only exist for requests with a body and application form urlencoded
        final Form form = (Form) httpContext.getProperties().get(FormDispatchProvider.FORM_PROPERTY);
        if (form == null) {
            for (final Map.Entry<String, String[]> entry : httpServletRequest.getParameterMap().entrySet()) {
                if (entry.getValue() == null) {
                    entity.add(entry.getKey(), null);
                } else {
                    for (final String aValue : entry.getValue()) {
                        entity.add(entry.getKey(), aValue);
                    }
                }
            }
        } else {
            entity.putAll(form);
        }

        return entity;
    }

    protected MultivaluedMap<String, String> getRequestParameters(final boolean forceClientId) {
        final MultivaluedMap<String, String> params = getRequestParameters();
        if (forceClientId) {
            if (StringUtils.isBlank(params.getFirst(CLIENT_ID))) {
                params.putSingle(CLIENT_ID, new ClientIdParameter().getClientId());
            }
        }
        return params;
    }

    protected Map<String, String> getHeaders() {
        return getHeaders(new HashMap<String, String>());
    }

    protected Map<String, String> getHeaders(final Map<String, String> overriddenHeaders) {

        final Map<String, String> result = new HashMap<>();
        final Map<String, String> overriddenHeadersIgnoreCaseMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (overriddenHeaders != null) {
            overriddenHeadersIgnoreCaseMap.putAll(overriddenHeaders);
        }

        final Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {

            final String headerName = headerNames.nextElement();
            if (!overriddenHeadersIgnoreCaseMap.isEmpty() && headerName.equalsIgnoreCase("content-length")) {
                continue;
            }
            if (overriddenHeadersIgnoreCaseMap.containsKey(headerName)) {
                result.put(headerName, overriddenHeadersIgnoreCaseMap.get(headerName));
            } else {
                result.put(headerName, httpServletRequest.getHeader(headerName));
            }
        }

        // set the proxy scheme to request scheme if not already set client
        final String proxyScheme = httpServletRequest.getHeader(PROXY_SCHEME_HTTP_HEADER);
        if (proxyScheme == null) {
            result.put(PROXY_SCHEME_HTTP_HEADER, httpServletRequest.getScheme());
        }

        return result;
    }

    /**
     * Checks whether the request is part of a two-phase commit style request (either phase 1 or phase 2)
     *
     * @param httpServletRequest the request
     * @return <code>true</code> if the request represents a two-phase commit style request
     */
    protected boolean isTwoPhaseRequest(final HttpServletRequest httpServletRequest) {
        final String headerValue = httpServletRequest.getHeader(RequestReplicator.REQUEST_TRANSACTION_ID_HEADER);
        return headerValue != null;
    }

    /**
     * When a two-phase commit style request is used, the first phase (generally referred to
     * as the "commit-request stage") is intended to validate that the request can be completed.
     * In NiFi, we use this phase to validate that the request can complete. This method determines
     * whether or not the request is the first phase of a two-phase commit.
     *
     * @param httpServletRequest the request
     * @return <code>true</code> if the request represents a two-phase commit style request and is the
     * first of the two phases.
     */
    protected boolean isValidationPhase(final HttpServletRequest httpServletRequest) {
        return isTwoPhaseRequest(httpServletRequest) && httpServletRequest.getHeader(RequestReplicator.REQUEST_VALIDATION_HTTP_HEADER) != null;
    }

    /**
     * Checks whether or not the request should be replicated to the cluster
     *
     * @return <code>true</code> if the request should be replicated, <code>false</code> otherwise
     */
    boolean isReplicateRequest() {
        // If not a node in a cluster, we do not replicate
        if (!properties.isNode()) {
            return false;
        }

        if (!isConnectedToCluster()) {
            return false;
        }

        // Check if the X-Request-Replicated header is set. If so, the request has already been replicated,
        // so we need to service the request locally. If not, then replicate the request to the entire cluster.
        final String header = httpServletRequest.getHeader(RequestReplicator.REPLICATION_INDICATOR_HEADER);
        return header == null;
    }

    /**
     * Converts a Revision DTO and an associated Component ID into a Revision object
     *
     * @param revisionDto the Revision DTO
     * @param componentId the ID of the component that the Revision DTO belongs to
     * @return a Revision that has the same client ID and Version as the Revision DTO and the Component ID specified
     */
    protected Revision getRevision(final RevisionDTO revisionDto, final String componentId) {
        return new Revision(revisionDto.getVersion(), revisionDto.getClientId(), componentId);
    }

    /**
     * Extracts a Revision object from the Revision DTO and ID provided by the Component Entity
     *
     * @param entity the ComponentEntity that contains the Revision DTO & ID
     * @return the Revision specified in the ComponentEntity
     */
    protected Revision getRevision(final ComponentEntity entity, final String componentId) {
        return getRevision(entity.getRevision(), componentId);
    }

    /**
     * Authorizes the specified Snippet with the specified request action.
     *
     * @param authorizer authorizer
     * @param lookup     lookup
     * @param action     action
     */
    protected void authorizeSnippet(final Snippet snippet, final Authorizer authorizer, final AuthorizableLookup lookup, final RequestAction action) {
        final Consumer<Authorizable> authorize = authorizable -> authorizable.authorize(authorizer, action, NiFiUserUtils.getNiFiUser());

        snippet.getProcessGroups().keySet().stream().map(id -> lookup.getProcessGroup(id)).forEach(authorize);
        snippet.getRemoteProcessGroups().keySet().stream().map(id -> lookup.getRemoteProcessGroup(id)).forEach(authorize);
        snippet.getProcessors().keySet().stream().map(id -> lookup.getProcessor(id)).forEach(authorize);
        snippet.getInputPorts().keySet().stream().map(id -> lookup.getInputPort(id)).forEach(authorize);
        snippet.getOutputPorts().keySet().stream().map(id -> lookup.getOutputPort(id)).forEach(authorize);
        snippet.getConnections().keySet().stream().map(id -> lookup.getConnection(id)).forEach(authorize);
        snippet.getConnections().keySet().stream().map(id -> lookup.getConnection(id)).forEach(authorize);
        snippet.getFunnels().keySet().stream().map(id -> lookup.getFunnel(id)).forEach(authorize);
    }

    /**
     * Authorizes the specified Snippet with the specified request action.
     *
     * @param authorizer authorizer
     * @param lookup     lookup
     * @param action     action
     */
    protected void authorizeSnippet(final SnippetDTO snippet, final Authorizer authorizer, final AuthorizableLookup lookup, final RequestAction action) {
        final Consumer<Authorizable> authorize = authorizable -> authorizable.authorize(authorizer, action, NiFiUserUtils.getNiFiUser());

        snippet.getProcessGroups().keySet().stream().map(id -> lookup.getProcessGroup(id)).forEach(authorize);
        snippet.getRemoteProcessGroups().keySet().stream().map(id -> lookup.getRemoteProcessGroup(id)).forEach(authorize);
        snippet.getProcessors().keySet().stream().map(id -> lookup.getProcessor(id)).forEach(authorize);
        snippet.getInputPorts().keySet().stream().map(id -> lookup.getInputPort(id)).forEach(authorize);
        snippet.getOutputPorts().keySet().stream().map(id -> lookup.getOutputPort(id)).forEach(authorize);
        snippet.getConnections().keySet().stream().map(id -> lookup.getConnection(id)).forEach(authorize);
        snippet.getConnections().keySet().stream().map(id -> lookup.getConnection(id)).forEach(authorize);
        snippet.getFunnels().keySet().stream().map(id -> lookup.getFunnel(id)).forEach(authorize);
    }

    /**
     * Executes an action through the service facade using the specified revision.
     *
     * @param serviceFacade service facade
     * @param revision      revision
     * @param authorizer    authorizer
     * @param verifier      verifier
     * @param action        executor
     * @return the response
     */
    protected Response withWriteLock(final NiFiServiceFacade serviceFacade, final Revision revision, final AuthorizeAccess authorizer,
                                     final Runnable verifier, final Supplier<Response> action) {

        final NiFiUser user = NiFiUserUtils.getNiFiUser();
        return withWriteLock(serviceFacade, authorizer, verifier, action,
            () -> serviceFacade.verifyRevision(revision, user));
    }

    /**
     * Executes an action through the service facade using the specified revision.
     *
     * @param serviceFacade service facade
     * @param revisions     revisions
     * @param authorizer    authorizer
     * @param verifier      verifier
     * @param action        executor
     * @return the response
     */
    protected Response withWriteLock(final NiFiServiceFacade serviceFacade, final Set<Revision> revisions, final AuthorizeAccess authorizer,
                                     final Runnable verifier, final Supplier<Response> action) {
        final NiFiUser user = NiFiUserUtils.getNiFiUser();
        return withWriteLock(serviceFacade, authorizer, verifier, action,
            () -> serviceFacade.verifyRevisions(revisions, user));
    }


    /**
     * Executes an action through the service facade using the specified revision.
     *
     * @param serviceFacade service facade
     * @param authorizer authorizer
     * @param verifier verifier
     * @param action the action to execute
     * @param verifyRevision a callback that will claim the necessary revisions for the operation
     * @return the response
     */
    private Response withWriteLock(
            final NiFiServiceFacade serviceFacade, final AuthorizeAccess authorizer, final Runnable verifier, final Supplier<Response> action,
        final Runnable verifyRevision) {

        final boolean validationPhase = isValidationPhase(httpServletRequest);
        if (validationPhase || !isTwoPhaseRequest(httpServletRequest)) {
            // authorize access
            serviceFacade.authorizeAccess(authorizer);
            verifyRevision.run();
        }

        if (validationPhase) {
            if (verifier != null) {
                verifier.run();
            }
            return generateContinueResponse().build();
        }

        return action.get();
    }

    /**
     * Replicates the request to the given node
     *
     * @param method   the HTTP method
     * @param nodeUuid the UUID of the node to replicate the request to
     * @return the response from the node
     * @throws UnknownNodeException if the nodeUuid given does not map to any node in the cluster
     */
    protected Response replicate(final String method, final String nodeUuid) {
        return replicate(method, getRequestParameters(true), nodeUuid);
    }

    /**
     * Replicates the request to the given node
     *
     * @param method   the HTTP method
     * @param entity   the Entity to replicate
     * @param nodeUuid the UUID of the node to replicate the request to
     * @return the response from the node
     * @throws UnknownNodeException if the nodeUuid given does not map to any node in the cluster
     */
    protected Response replicate(final String method, final Object entity, final String nodeUuid) {
        return replicate(method, entity, nodeUuid, null);
    }

    /**
     * Replicates the request to the given node
     *
     * @param method   the HTTP method
     * @param entity   the Entity to replicate
     * @param nodeUuid the UUID of the node to replicate the request to
     * @return the response from the node
     * @throws UnknownNodeException if the nodeUuid given does not map to any node in the cluster
     */
    protected Response replicate(final String method, final Object entity, final String nodeUuid, final Map<String, String> headersToOverride) {
        // since we're cluster we must specify the cluster node identifier
        if (nodeUuid == null) {
            throw new IllegalArgumentException("The cluster node identifier must be specified.");
        }

        final NodeIdentifier nodeId = clusterCoordinator.getNodeIdentifier(nodeUuid);
        if (nodeId == null) {
            throw new UnknownNodeException("Cannot replicate request " + method + " " + getAbsolutePath() + " to node with ID " + nodeUuid + " because the specified node does not exist.");
        }

        final URI path = getAbsolutePath();
        try {
            final Map<String, String> headers = headersToOverride == null ? getHeaders() : getHeaders(headersToOverride);

            // Determine if we should replicate to the node directly or if we should replicate to the Cluster Coordinator,
            // and have it replicate the request on our behalf.
            if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
                // If we are to replicate directly to the nodes, we need to indicate that the replication source is
                // the cluster coordinator so that the node knows to service the request.
                final Set<NodeIdentifier> targetNodes = Collections.singleton(nodeId);
                return requestReplicator.replicate(targetNodes, method, path, entity, headers, true).awaitMergedResponse().getResponse();
            } else {
                headers.put(RequestReplicator.REPLICATION_TARGET_NODE_UUID_HEADER, nodeId.getId());
                return requestReplicator.replicate(Collections.singleton(getClusterCoordinatorNode()), method,
                    path, entity, headers, false).awaitMergedResponse().getResponse();
            }
        } catch (final InterruptedException ie) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Request to " + method + " " + path + " was interrupted").type("text/plain").build();
        }
    }

    protected NodeIdentifier getClusterCoordinatorNode() {
        final NodeIdentifier activeClusterCoordinator = clusterCoordinator.getElectedActiveCoordinatorNode();
        if (activeClusterCoordinator != null) {
            return activeClusterCoordinator;
        }

        throw new NoClusterCoordinatorException();
    }

    protected ReplicationTarget getReplicationTarget() {
        return clusterCoordinator.isActiveClusterCoordinator() ? ReplicationTarget.CLUSTER_NODES : ReplicationTarget.CLUSTER_COORDINATOR;
    }

    protected Response replicate(final String method, final NodeIdentifier targetNode) {
        try {
            // Determine whether we should replicate only to the cluster coordinator, or if we should replicate directly
            // to the cluster nodes themselves.
            if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
                final Set<NodeIdentifier> nodeIds = Collections.singleton(targetNode);
                return getRequestReplicator().replicate(nodeIds, method, getAbsolutePath(), getRequestParameters(true), getHeaders(), true).awaitMergedResponse().getResponse();
            } else {
                final Set<NodeIdentifier> coordinatorNode = Collections.singleton(getClusterCoordinatorNode());
                final Map<String, String> headers = getHeaders(Collections.singletonMap(RequestReplicator.REPLICATION_TARGET_NODE_UUID_HEADER, targetNode.getId()));
                return getRequestReplicator().replicate(coordinatorNode, method, getAbsolutePath(), getRequestParameters(true), headers, false).awaitMergedResponse().getResponse();
            }
        } catch (final InterruptedException ie) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Request to " + method + " " + getAbsolutePath() + " was interrupted").type("text/plain").build();
        }
    }

    /**
     * Convenience method for calling {@link #replicate(String, Object)} with an entity of
     * {@link #getRequestParameters() getRequestParameters(true)}
     *
     * @param method the HTTP method to use
     * @return the response from the request
     */
    protected Response replicate(final String method) {
        return replicate(method, getRequestParameters(true));
    }

    /**
     * Convenience method for calling {@link #replicateNodeResponse(String, Object, Map)} with an entity of
     * {@link #getRequestParameters() getRequestParameters(true)} and overriding no headers
     *
     * @param method the HTTP method to use
     * @return the response from the request
     * @throws InterruptedException if interrupted while replicating the request
     */
    protected NodeResponse replicateNodeResponse(final String method) throws InterruptedException {
        return replicateNodeResponse(method, getRequestParameters(true), (Map<String, String>) null);
    }

    /**
     * Replicates the request to all nodes in the cluster using the provided method and entity. The headers
     * used will be those provided by the {@link #getHeaders()} method. The URI that will be used will be
     * that provided by the {@link #getAbsolutePath()} method
     *
     * @param method the HTTP method to use
     * @param entity the entity to replicate
     * @return the response from the request
     */
    protected Response replicate(final String method, final Object entity) {
        return replicate(method, entity, (Map<String, String>) null);
    }

    /**
     * Replicates the request to all nodes in the cluster using the provided method and entity. The headers
     * used will be those provided by the {@link #getHeaders()} method. The URI that will be used will be
     * that provided by the {@link #getAbsolutePath()} method
     *
     * @param method the HTTP method to use
     * @param entity the entity to replicate
     * @param headersToOverride the headers to override
     * @return the response from the request
     * @see #replicateNodeResponse(String, Object, Map)
     */
    protected Response replicate(final String method, final Object entity, final Map<String, String> headersToOverride) {
        try {
            return replicateNodeResponse(method, entity, headersToOverride).getResponse();
        } catch (final InterruptedException ie) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Request to " + method + " " + getAbsolutePath() + " was interrupted").type("text/plain").build();
        }
    }

    /**
     * Replicates the request to all nodes in the cluster using the provided method and entity. The headers
     * used will be those provided by the {@link #getHeaders()} method. The URI that will be used will be
     * that provided by the {@link #getAbsolutePath()} method. This method returns the NodeResponse,
     * rather than a Response object.
     *
     * @param method the HTTP method to use
     * @param entity the entity to replicate
     * @param headersToOverride the headers to override
     *
     * @return the response from the request
     *
     * @throws InterruptedException if interrupted while replicating the request
     * @see #replicate(String, Object, Map)
     */
    protected NodeResponse replicateNodeResponse(final String method, final Object entity, final Map<String, String> headersToOverride) throws InterruptedException {
        final URI path = getAbsolutePath();
        final Map<String, String> headers = headersToOverride == null ? getHeaders() : getHeaders(headersToOverride);

        // Determine whether we should replicate only to the cluster coordinator, or if we should replicate directly
        // to the cluster nodes themselves.
        if (getReplicationTarget() == ReplicationTarget.CLUSTER_NODES) {
            return requestReplicator.replicate(method, path, entity, headers).awaitMergedResponse();
        } else {
            return requestReplicator.replicate(Collections.singleton(getClusterCoordinatorNode()), method, path, entity, headers, false).awaitMergedResponse();
        }
    }

    /**
     * @return <code>true</code> if connected to a cluster, <code>false</code>
     * if running in standalone mode or disconnected from cluster
     */
    boolean isConnectedToCluster() {
        return clusterCoordinator != null && clusterCoordinator.isConnected();
    }

    public void setRequestReplicator(final RequestReplicator requestReplicator) {
        this.requestReplicator = requestReplicator;
    }

    protected RequestReplicator getRequestReplicator() {
        return requestReplicator;
    }

    public void setProperties(final NiFiProperties properties) {
        this.properties = properties;
    }

    public void setClusterCoordinator(final ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
    }

    protected ClusterCoordinator getClusterCoordinator() {
        return clusterCoordinator;
    }

    protected NiFiProperties getProperties() {
        return properties;
    }

    public static enum ReplicationTarget {
        CLUSTER_NODES, CLUSTER_COORDINATOR;
    }

    // -----------------
    // HTTP site to site
    // -----------------

    protected Integer negotiateTransportProtocolVersion(final HttpServletRequest req, final VersionNegotiator transportProtocolVersionNegotiator) throws BadRequestException {
        String protocolVersionStr = req.getHeader(HttpHeaders.PROTOCOL_VERSION);
        if (isEmpty(protocolVersionStr)) {
            throw new BadRequestException("Protocol version was not specified.");
        }

        final Integer requestedProtocolVersion;
        try {
            requestedProtocolVersion = Integer.valueOf(protocolVersionStr);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Specified protocol version was not in a valid number format: " + protocolVersionStr);
        }

        Integer protocolVersion;
        if (transportProtocolVersionNegotiator.isVersionSupported(requestedProtocolVersion)) {
            return requestedProtocolVersion;
        } else {
            protocolVersion = transportProtocolVersionNegotiator.getPreferredVersion(requestedProtocolVersion);
        }

        if (protocolVersion == null) {
            throw new BadRequestException("Specified protocol version is not supported: " + protocolVersionStr);
        }
        return protocolVersion;
    }

    protected Response.ResponseBuilder setCommonHeaders(final Response.ResponseBuilder builder, final Integer transportProtocolVersion, final HttpRemoteSiteListener transactionManager) {
        return builder.header(HttpHeaders.PROTOCOL_VERSION, transportProtocolVersion)
                .header(HttpHeaders.SERVER_SIDE_TRANSACTION_TTL, transactionManager.getTransactionTtlSec());
    }

    protected class ResponseCreator {

        public Response nodeTypeErrorResponse(String errMsg) {
            return noCache(Response.status(Response.Status.FORBIDDEN)).type(MediaType.TEXT_PLAIN).entity(errMsg).build();
        }

        public Response httpSiteToSiteIsNotEnabledResponse() {
            return noCache(Response.status(Response.Status.FORBIDDEN)).type(MediaType.TEXT_PLAIN).entity("HTTP(S) Site-to-Site is not enabled on this host.").build();
        }

        public Response wrongPortTypeResponse(String portType, String portId) {
            logger.debug("Port type was wrong. portType={}, portId={}", portType, portId);
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.ABORT.getCode());
            entity.setMessage("Port was not found.");
            entity.setFlowFileSent(0);
            return Response.status(NOT_FOUND).entity(entity).type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        public Response transactionNotFoundResponse(String portId, String transactionId) {
            logger.debug("Transaction was not found. portId={}, transactionId={}", portId, transactionId);
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.ABORT.getCode());
            entity.setMessage("Transaction was not found.");
            entity.setFlowFileSent(0);
            return Response.status(NOT_FOUND).entity(entity).type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        public Response unexpectedErrorResponse(String portId, Exception e) {
            logger.error("Unexpected exception occurred. portId={}", portId);
            logger.error("Exception detail:", e);
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.ABORT.getCode());
            entity.setMessage("Server encountered an exception.");
            entity.setFlowFileSent(0);
            return Response.serverError().entity(entity).type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        public Response unexpectedErrorResponse(String portId, String transactionId, Exception e) {
            logger.error("Unexpected exception occurred. portId={}, transactionId={}", portId, transactionId);
            logger.error("Exception detail:", e);
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.ABORT.getCode());
            entity.setMessage("Server encountered an exception.");
            entity.setFlowFileSent(0);
            return Response.serverError().entity(entity).type(MediaType.APPLICATION_JSON_TYPE).build();
        }

        public Response unauthorizedResponse(NotAuthorizedException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Client request was not authorized. {}", e.getMessage());
            }
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.UNAUTHORIZED.getCode());
            entity.setMessage(e.getMessage());
            entity.setFlowFileSent(0);
            return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON_TYPE).entity(e.getMessage()).build();
        }

        public Response badRequestResponse(Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Client sent a bad request. {}", e.getMessage());
            }
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(ResponseCode.ABORT.getCode());
            entity.setMessage(e.getMessage());
            entity.setFlowFileSent(0);
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
        }

        public Response handshakeExceptionResponse(HandshakeException e) {
            if(logger.isDebugEnabled()){
                logger.debug("Handshake failed, {}", e.getMessage());
            }
            ResponseCode handshakeRes = e.getResponseCode();
            Response.Status statusCd;
            TransactionResultEntity entity = new TransactionResultEntity();
            entity.setResponseCode(handshakeRes != null ? handshakeRes.getCode() : ResponseCode.ABORT.getCode());
            entity.setMessage(e.getMessage());
            entity.setFlowFileSent(0);
            switch (handshakeRes) {
                case PORT_NOT_IN_VALID_STATE:
                case PORTS_DESTINATION_FULL:
                    return Response.status(Response.Status.SERVICE_UNAVAILABLE).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
                case UNAUTHORIZED:
                    statusCd = Response.Status.UNAUTHORIZED;
                    break;
                case UNKNOWN_PORT:
                    statusCd = NOT_FOUND;
                    break;
                default:
                    statusCd = Response.Status.BAD_REQUEST;
            }
            return Response.status(statusCd).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
        }

        public Response acceptedResponse(final HttpRemoteSiteListener transactionManager, final Object entity, final Integer protocolVersion) {
            return noCache(setCommonHeaders(Response.status(Response.Status.ACCEPTED), protocolVersion, transactionManager))
                    .entity(entity).build();
        }

        public Response locationResponse(UriInfo uriInfo, String portType, String portId, String transactionId, Object entity,
                                         Integer protocolVersion, final HttpRemoteSiteListener transactionManager) {

            String path = "/data-transfer/" + portType + "/" + portId + "/transactions/" + transactionId;
            URI location = uriInfo.getBaseUriBuilder().path(path).build();
            return noCache(setCommonHeaders(Response.created(location), protocolVersion, transactionManager)
                    .header(LOCATION_URI_INTENT_NAME, LOCATION_URI_INTENT_VALUE))
                    .entity(entity).build();
        }

    }
}
