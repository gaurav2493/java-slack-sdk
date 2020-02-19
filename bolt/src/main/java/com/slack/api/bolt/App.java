package com.slack.api.bolt;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.slack.api.Slack;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.app_backend.events.EventHandler;
import com.slack.api.app_backend.events.EventsDispatcher;
import com.slack.api.app_backend.events.EventsDispatcherFactory;
import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.handler.BoltEventHandler;
import com.slack.api.bolt.handler.WebEndpointHandler;
import com.slack.api.bolt.handler.builtin.*;
import com.slack.api.bolt.middleware.Middleware;
import com.slack.api.bolt.middleware.builtin.*;
import com.slack.api.bolt.request.Request;
import com.slack.api.bolt.request.builtin.*;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.service.InstallationService;
import com.slack.api.bolt.service.OAuthCallbackService;
import com.slack.api.bolt.service.OAuthStateService;
import com.slack.api.bolt.service.builtin.ClientOnlyOAuthStateService;
import com.slack.api.bolt.service.builtin.DefaultOAuthCallbackService;
import com.slack.api.bolt.service.builtin.FileInstallationService;
import com.slack.api.bolt.service.builtin.oauth.*;
import com.slack.api.bolt.service.builtin.oauth.default_impl.*;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.event.Event;
import com.slack.api.util.json.GsonFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * A Slack App instance.
 */
@Slf4j
@AllArgsConstructor
@Builder(toBuilder = true)
public class App {

    /**
     * The default JSON utility.
     */
    private static final Gson GSON = GsonFactory.createSnakeCase();

    /**
     * The given AppConfig in this App.
     */
    private final AppConfig appConfig;

    /**
     * Slack instance and its configurations to be used for API calls.
     */
    private final Slack slack;

    // --------------------------------------
    // Middleware
    // --------------------------------------

    /**
     * Registered middleware.
     */
    private List<Middleware> middlewareList;

    protected List<Middleware> buildDefaultMiddlewareList(AppConfig appConfig) {
        List<Middleware> middlewareList = new ArrayList<>();

        // ssl_check (slash command)
        middlewareList.add(new SSLCheck(config().getVerificationToken()));

        // request verification
        // https://api.slack.com/docs/verifying-requests-from-slack
        SlackSignature.Verifier verifier = new SlackSignature.Verifier(new SlackSignature.Generator(appConfig.getSigningSecret()));
        RequestVerification requestVerification = new RequestVerification(verifier);
        middlewareList.add(requestVerification);

        // single team authorization
        if (appConfig.isDistributedApp()) {
            middlewareList.add(new MultiTeamsAuthorization(config(), installationService));
        } else if (appConfig.getSingleTeamBotToken() != null) {
            middlewareList.add(new SingleTeamAuthorization(config(), installationService));
        } else {
            log.warn("Skipped adding any authorization middleware - you need to call `app.use(new YourOwnMultiTeamsAuthorization())`");
        }

        // ignoring the events generated by this bot user
        middlewareList.add(new IgnoringSelfEvents());

        return middlewareList;
    }

    // --------------------------------------
    // App Status
    // --------------------------------------

    public enum Status {
        Running,
        Stopped
    }

    /**
     * Current status of this App.
     */
    private Status status = Status.Stopped;

    // -------------------------------------
    // Slash Commands
    // https://api.slack.com/interactivity/slash-commands
    // -------------------------------------

    /**
     * Registered slash command handlers.
     */
    private final Map<Pattern, SlashCommandHandler> slashCommandHandlers = new HashMap<>();

    // -------------------------------------
    // Events API
    // https://api.slack.com/events-api
    // -------------------------------------

    /**
     * Registered Event API handlers.
     */
    private final Map<String, BoltEventHandler<Event>> eventHandlers = new HashMap<>();

    /**
     * Primitive Event API handler.
     */
    private final EventsDispatcher eventsDispatcher = EventsDispatcherFactory.getInstance();

    /**
     * Cached mapping between Event data types and their "{type}:{subtype}" values.
     */
    private Map<Class<? extends Event>, String> eventTypeAndSubtypeValues = new HashMap<>();

    private Class<? extends Event> getEventClass(String eventType) {
        for (Map.Entry<Class<? extends Event>, String> entry : eventTypeAndSubtypeValues.entrySet()) {
            if (entry.getValue().equals(eventType)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String getEventTypeAndSubtype(Class<? extends Event> clazz) {
        String cached = eventTypeAndSubtypeValues.get(clazz);
        if (cached != null) {
            return cached;
        } else {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 0) {
                    try {
                        Event event = (Event) constructor.newInstance();
                        String typeAndSubtype = event.getType() + ":" + event.getSubtype();
                        eventTypeAndSubtypeValues.put(clazz, typeAndSubtype);
                        return typeAndSubtype;
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                        log.error("Unexpectedly failed to load event type for the class {}", clazz.getCanonicalName());
                        break;
                    }
                }
            }
        }
        return null;
    }

    private BoltEventPayload buildEventPayload(EventRequest request) {
        BoltEventPayload payload = GSON.fromJson(request.getRequestBodyAsString(), BoltEventPayload.class);
        Class<? extends Event> eventClass = getEventClass(request.getEventTypeAndSubtype());
        if (eventClass != null) {
            Event event = GSON.fromJson(GSON.fromJson(request.getRequestBodyAsString(), JsonElement.class).getAsJsonObject().get("event").getAsJsonObject(), eventClass);
            payload.setEvent(event);
        }
        return payload;
    }

    @Data
    static class BoltEventPayload implements EventsApiPayload<Event> {
        private String token;
        private String enterpriseId;
        private String teamId;
        private String apiAppId;
        private String type;
        private List<String> authedUsers;
        private List<String> authedTeams;
        private String eventId;
        private Integer eventTime;

        private transient Event event;
    }

    // -------------------------------------
    // Block Kit
    // https://api.slack.com/block-kit
    // -------------------------------------

    /**
     * Registered handlers for block actions (type: "block_actions") in Block Kit.
     */
    private final Map<Pattern, BlockActionHandler> blockActionHandlers = new HashMap<>();
    /**
     * Registered handlers for external data source select options (type: "block_suggestions") in Block Kit.
     */
    private final Map<Pattern, BlockSuggestionHandler> blockSuggestionHandlers = new HashMap<>();

    // -------------------------------------
    // Modal Views
    // https://api.slack.com/surfaces/modals/using
    // -------------------------------------

    /**
     * Registered handlers for modal submissions.
     */
    private final Map<Pattern, ViewSubmissionHandler> viewSubmissionHandlers = new HashMap<>();
    /**
     * Registered handlers for modal cancellations.
     */
    private final Map<Pattern, ViewClosedHandler> viewClosedHandlers = new HashMap<>();

    // -------------------------------------
    // Message Actions
    // https://api.slack.com/interactivity/actions
    // -------------------------------------

    /**
     * Registered handlers for message actions.
     */
    private final Map<Pattern, MessageActionHandler> messageActionHandlers = new HashMap<>();

    // -------------------------------------
    // Attachments
    // https://api.slack.com/messaging/composing/layouts#attachments
    // -------------------------------------

    /**
     * Registered attachment action (type: "interactive_message"") handlers.
     */
    private final Map<Pattern, AttachmentActionHandler> attachmentActionHandlers = new HashMap<>();

    // -------------------------------------
    // Dialogs
    // https://api.slack.com/dialogs
    // -------------------------------------

    /**
     * Registered handlers for dialog submissions.
     */
    private final Map<Pattern, DialogSubmissionHandler> dialogSubmissionHandlers = new HashMap<>();
    /**
     * Registered handlers for external data source select options in dialogs.
     */
    private final Map<Pattern, DialogSuggestionHandler> dialogSuggestionHandlers = new HashMap<>();
    /**
     * Registered handlers for dialog cancellations.
     */
    private final Map<Pattern, DialogCancellationHandler> dialogCancellationHandlers = new HashMap<>();

    // -------------------------------------
    // Outgoing Webhooks
    // https://api.slack.com/legacy/custom-integrations/outgoing-webhooks
    // -------------------------------------

    /**
     * Registered handlers for outgoing webhooks (custom integrations).
     */
    private final Map<String, OutgoingWebhooksHandler> outgoingWebhooksHandlers = new HashMap<>();

    // -------------------------------------
    // Installation information / OAuth access tokens
    // -------------------------------------

    private InstallationService installationService; // will be initialized in the constructor

    // -------------------------------------
    // OAuth Flow
    // -------------------------------------

    private OAuthStateService oAuthStateService = new ClientOnlyOAuthStateService();
    private OAuthCallbackService oAuthCallbackService = null;

    private OAuthSuccessHandler oAuthSuccessHandler; // will be initialized in the constructor
    private OAuthV2SuccessHandler oAuthV2SuccessHandler; // will be initialized in the constructor
    private OAuthErrorHandler oAuthErrorHandler = new OAuthDefaultErrorHandler();
    private OAuthAccessErrorHandler oAuthAccessErrorHandler = new OAuthDefaultAccessErrorHandler();
    private OAuthV2AccessErrorHandler oAuthV2AccessErrorHandler = new OAuthV2DefaultAccessErrorHandler();
    private OAuthStateErrorHandler oAuthStateErrorHandler = new OAuthDefaultStateErrorHandler();
    private OAuthExceptionHandler oAuthExceptionHandler = new OAuthDefaultExceptionHandler();

    private void initOAuthServicesIfNecessary() {
        if (appConfig.isDistributedApp() && appConfig.isOAuthCallbackEnabled()) {
            if (this.oAuthCallbackService == null) {
                this.oAuthCallbackService = new DefaultOAuthCallbackService(
                        config(),
                        oAuthStateService,
                        oAuthSuccessHandler,
                        oAuthV2SuccessHandler,
                        oAuthErrorHandler,
                        oAuthStateErrorHandler,
                        oAuthAccessErrorHandler,
                        oAuthV2AccessErrorHandler,
                        oAuthExceptionHandler
                );
            }
        }
    }

    public String getOauthInstallationUrl(String state) {
        AppConfig config = config();
        if (config.getClientId() == null || config.getScope() == null || state == null) {
            return null;
        } else {
            if (config.isClassicAppPermissionsEnabled()) {
                // https://api.slack.com/authentication/migration
                return "https://slack.com/oauth/authorize" +
                        "?client_id=" + config.getClientId() +
                        "&scope=" + config.getScope() +
                        "&state=" + state;
            } else {
                return "https://slack.com/oauth/v2/authorize" +
                        "?client_id=" + config.getClientId() +
                        "&scope=" + config.getScope() +
                        "&user_scope=" + config.getUserScope() +
                        "&state=" + state;
            }
        }
    }

    // -------------------------------------
    // Additional Web Endpoints
    // -------------------------------------

    private final Map<WebEndpoint, WebEndpointHandler> webEndpointHandlers = new HashMap<>();

    public Map<WebEndpoint, WebEndpointHandler> getWebEndpointHandlers() {
        return webEndpointHandlers;
    }

    // --------------------------------------
    // constructors
    // --------------------------------------

    public App() {
        this(new AppConfig());
    }

    public App(AppConfig appConfig) {
        this(appConfig, null);
        asOAuthApp(false); // disabled by default as the default mode would be for single team app
    }

    public App(AppConfig appConfig, List<Middleware> middlewareList) {
        this(appConfig, appConfig.getSlack() != null ? appConfig.getSlack() : Slack.getInstance(), middlewareList);
    }

    public App(AppConfig appConfig, Slack slack, List<Middleware> middlewareList) {
        this.appConfig = appConfig;
        this.slack = slack;
        this.middlewareList = middlewareList;

        this.installationService = new FileInstallationService(this.appConfig);
        this.oAuthSuccessHandler = new OAuthDefaultSuccessHandler(this.installationService);
        this.oAuthV2SuccessHandler = new OAuthV2DefaultSuccessHandler(this.installationService);
    }

    // --------------------------------------
    // public methods
    // --------------------------------------

    public AppConfig config() {
        return this.appConfig;
    }

    public App.Status status() {
        return this.status;
    }

    private AtomicBoolean neverStarted = new AtomicBoolean(true);

    public App start() {
        synchronized (status) {
            if (status == Status.Stopped) {
                if (middlewareList == null) {
                    middlewareList = buildDefaultMiddlewareList(appConfig);
                }
                initOAuthServicesIfNecessary();

                if (!this.eventsDispatcher.isEmpty()) {
                    this.eventsDispatcher.start();
                }
                neverStarted.set(false);
            }
            status = Status.Running;
        }
        return this;
    }

    public App stop() {
        synchronized (status) {
            if (status == Status.Running) {
                if (this.eventsDispatcher.isRunning()) {
                    this.eventsDispatcher.stop();
                }
            }
            status = Status.Stopped;
        }
        return this;
    }

    public Response run(Request request) throws Exception {
        request.getContext().setSlack(this.slack); // use the properly configured API client

        if (neverStarted.get()) {
            start();
        }
        LinkedList<Middleware> remaining = new LinkedList<>(middlewareList);
        if (remaining.isEmpty()) {
            return runHandler(request);
        } else {
            Middleware firstMiddleware = remaining.pop();
            return runMiddleware(request, Response.ok(), firstMiddleware, remaining);
        }
    }

    // ----------------------
    // Middleware registration methods

    public App use(Middleware middleware) {
        if (this.middlewareList == null) {
            this.middlewareList = buildDefaultMiddlewareList(config());
        }
        this.middlewareList.add(middleware);
        return this;
    }

    // ----------------------
    // App routing methods

    // -------------
    // Events API
    // https://api.slack.com/events-api

    public <E extends Event> App event(Class<E> eventClass, BoltEventHandler<E> handler) {
        String eventTypeAndSubtype = getEventTypeAndSubtype(eventClass);
        if (eventTypeAndSubtype == null) {
            throw new IllegalArgumentException("Unexpectedly failed to register the handler");
        }
        if (eventHandlers.get(eventTypeAndSubtype) != null) {
            log.warn("Replaced the handler for {}", eventTypeAndSubtype);
        }
        eventHandlers.put(eventTypeAndSubtype, (BoltEventHandler<Event>) handler);
        return this;
    }

    public App event(EventHandler<?> handler) {
        eventsDispatcher.register(handler);
        return this;
    }

    // -------------
    // Slash Commands
    // https://api.slack.com/interactivity/slash-commands

    public App command(String command, SlashCommandHandler handler) {
        return command(Pattern.compile("^" + command + "$"), handler);
    }

    public App command(Pattern command, SlashCommandHandler handler) {
        if (slashCommandHandlers.get(command) != null) {
            log.warn("Replaced the handler for {}", command);
        }
        slashCommandHandlers.put(command, handler);
        return this;
    }

    // -------------
    // Block Kit
    // https://api.slack.com/block-kit

    public App blockAction(String actionId, BlockActionHandler handler) {
        return blockAction(Pattern.compile("^" + actionId + "$"), handler);
    }

    public App blockAction(Pattern actionId, BlockActionHandler handler) {
        if (blockActionHandlers.get(actionId) != null) {
            log.warn("Replaced the handler for {}", actionId);
        }
        blockActionHandlers.put(actionId, handler);
        return this;
    }

    public App blockSuggestion(String actionId, BlockSuggestionHandler handler) {
        return blockSuggestion(Pattern.compile("^" + actionId + "$"), handler);
    }

    public App blockSuggestion(Pattern actionId, BlockSuggestionHandler handler) {
        if (blockSuggestionHandlers.get(actionId) != null) {
            log.warn("Replaced the handler for {}", actionId);
        }
        blockSuggestionHandlers.put(actionId, handler);
        return this;
    }

    // -------------
    // Modal Views
    // https://api.slack.com/surfaces/modals/using

    public App viewSubmission(String callbackId, ViewSubmissionHandler handler) {
        return viewSubmission(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App viewSubmission(Pattern callbackId, ViewSubmissionHandler handler) {
        if (viewSubmissionHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        viewSubmissionHandlers.put(callbackId, handler);
        return this;
    }

    public App viewClosed(String callbackId, ViewClosedHandler handler) {
        return viewClosed(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App viewClosed(Pattern callbackId, ViewClosedHandler handler) {
        if (viewClosedHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        viewClosedHandlers.put(callbackId, handler);
        return this;
    }

    // -------------
    // Message Actions
    // https://api.slack.com/interactivity/actions

    public App messageAction(String callbackId, MessageActionHandler handler) {
        return messageAction(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App messageAction(Pattern callbackId, MessageActionHandler handler) {
        if (messageActionHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        messageActionHandlers.put(callbackId, handler);
        return this;
    }

    // -------------
    // Attachments
    // https://api.slack.com/messaging/composing/layouts#attachments

    public App attachmentAction(String callbackId, AttachmentActionHandler handler) {
        return attachmentAction(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App attachmentAction(Pattern callbackId, AttachmentActionHandler handler) {
        if (attachmentActionHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        attachmentActionHandlers.put(callbackId, handler);
        return this;
    }

    // -------------
    // Dialogs
    // https://api.slack.com/dialogs

    public App dialogSubmission(String callbackId, DialogSubmissionHandler handler) {
        return dialogSubmission(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App dialogSubmission(Pattern callbackId, DialogSubmissionHandler handler) {
        if (dialogSubmissionHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        dialogSubmissionHandlers.put(callbackId, handler);
        return this;
    }

    public App dialogSuggestion(String callbackId, DialogSuggestionHandler handler) {
        return dialogSuggestion(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App dialogSuggestion(Pattern callbackId, DialogSuggestionHandler handler) {
        if (dialogSuggestionHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        dialogSuggestionHandlers.put(callbackId, handler);
        return this;
    }

    public App dialogCancellation(String callbackId, DialogCancellationHandler handler) {
        return dialogCancellation(Pattern.compile("^" + callbackId + "$"), handler);
    }

    public App dialogCancellation(Pattern callbackId, DialogCancellationHandler handler) {
        if (dialogCancellationHandlers.get(callbackId) != null) {
            log.warn("Replaced the handler for {}", callbackId);
        }
        dialogCancellationHandlers.put(callbackId, handler);
        return this;
    }

    // -------------
    // Outgoing Webhooks
    // https://api.slack.com/legacy/custom-integrations/outgoing-webhooks

    public App webhook(String triggerWord, OutgoingWebhooksHandler handler) {
        if (outgoingWebhooksHandlers.get(triggerWord) != null) {
            log.warn("Replaced the handler for {}", triggerWord);
        }
        outgoingWebhooksHandlers.put(triggerWord, handler);
        return this;
    }

    // ----------------------
    // OAuth App configuration methods

    public App asOAuthApp(boolean enabled) {
        config().setOAuthStartEnabled(enabled);
        config().setOAuthCallbackEnabled(enabled);
        return this;
    }

    public App service(OAuthCallbackService oAuthCallbackService) {
        this.oAuthCallbackService = oAuthCallbackService;
        return this;
    }

    public App service(OAuthStateService oAuthStateService) {
        this.oAuthStateService = oAuthStateService;
        return this;
    }

    public App service(InstallationService installationService) {
        this.installationService = installationService;
        if (config().isClassicAppPermissionsEnabled()) {
            return oauthCallback(new OAuthDefaultSuccessHandler(installationService));
        } else {
            return oauthCallback(new OAuthV2DefaultSuccessHandler(installationService));
        }
    }

    public App oauthCallback(OAuthSuccessHandler handler) {
        oAuthSuccessHandler = handler;
        return this;
    }

    public App oauthCallback(OAuthV2SuccessHandler handler) {
        oAuthV2SuccessHandler = handler;
        return this;
    }

    public App oauthCallbackError(OAuthErrorHandler handler) {
        oAuthErrorHandler = handler;
        return this;
    }

    public App oauthCallbackStateError(OAuthStateErrorHandler handler) {
        oAuthStateErrorHandler = handler;
        return this;
    }

    public App oauthCallbackAccessError(OAuthAccessErrorHandler handler) {
        oAuthAccessErrorHandler = handler;
        return this;
    }

    public App oauthCallbackAccessError(OAuthV2AccessErrorHandler handler) {
        oAuthV2AccessErrorHandler = handler;
        return this;
    }

    public App oauthCallbackException(OAuthExceptionHandler handler) {
        oAuthExceptionHandler = handler;
        return this;
    }

    public App toOAuthStartApp() {
        App newApp = toBuilder().appConfig(config().toBuilder().build()).build();
        newApp.config().setOAuthStartEnabled(true);
        newApp.config().setOAuthCallbackEnabled(false);
        return newApp;
    }

    public App toOAuthCallbackApp() {
        App newApp = toBuilder().appConfig(config().toBuilder().build()).build();
        newApp.config().setOAuthStartEnabled(false);
        newApp.config().setOAuthCallbackEnabled(true);
        return newApp;
    }

    // ----------------------
    // Additional endpoint support (e.g., health check endpoints)

    public App endpoint(String path, WebEndpointHandler handler) {
        return endpoint(WebEndpoint.Method.GET, path, handler);
    }

    public App endpoint(String method, String path, WebEndpointHandler handler) {
        return endpoint(WebEndpoint.Method.valueOf(method), path, handler);
    }

    public App endpoint(WebEndpoint.Method method, String path, WebEndpointHandler handler) {
        webEndpointHandlers.put(new WebEndpoint(method, path), handler);
        return this;
    }

    // --------------------------------------
    // Internal Runner Methods
    // --------------------------------------

    // recursively runs the remaining middleware
    protected Response runMiddleware(
            Request request,
            Response response,
            Middleware current,
            LinkedList<Middleware> remaining) throws Exception {
        if (remaining.isEmpty()) {
            return current.apply(request, response, (req) -> runHandler(req));
        } else {
            Middleware next = remaining.pop();
            return current.apply(request, response, (req) -> runMiddleware(request, response, next, remaining));
        }
    }

    protected Response runHandler(Request slackRequest) throws IOException, SlackApiException {
        switch (slackRequest.getRequestType()) {
            case OAuthStart: {
                if (config().isDistributedApp()) {
                    try {
                        Map<String, List<String>> responseHeaders = new HashMap<>();
                        Response response = Response.builder().statusCode(302).headers(responseHeaders).build();
                        String state = oAuthStateService.issueNewState(slackRequest, response);
                        String url = getOauthInstallationUrl(state);
                        if (url == null) {
                            log.error("AppConfig#getOauthInstallationUrl(String) returned null due to some missing settings");
                            responseHeaders.put("Location", Arrays.asList(config().getOauthCancellationUrl()));
                        } else {
                            responseHeaders.put("Location", Arrays.asList(url));
                        }
                        return response;
                    } catch (Exception e) {
                        log.error("Failed to run the operation (error: {})", e.getMessage(), e);
                    }
                }
                log.warn("Skipped to handle an OAuth callback request as this Bolt app is not ready for it");
                return Response.builder().statusCode(500).body("something wrong").build();
            }
            case OAuthCallback: {
                if (config().isDistributedApp()) {
                    if (oAuthCallbackService != null) {
                        OAuthCallbackRequest request = (OAuthCallbackRequest) slackRequest;
                        return oAuthCallbackService.handle(request);
                    }
                }
                log.warn("Skipped to handle an OAuth callback request as this Bolt app is not ready for it");
                return Response.builder().statusCode(500).body("something wrong").build();
            }
            case Command: {
                SlashCommandRequest request = (SlashCommandRequest) slackRequest;
                String command = request.getPayload().getCommand();
                for (Pattern pattern : slashCommandHandlers.keySet()) {
                    if (pattern.matcher(command).matches()) {
                        SlashCommandHandler handler = slashCommandHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No SlashCommandHandler registered for command: {}", request.getPayload().getCommand());
                break;
            }
            case OutgoingWebhooks: {
                OutgoingWebhooksRequest request = (OutgoingWebhooksRequest) slackRequest;
                OutgoingWebhooksHandler handler = outgoingWebhooksHandlers.get(request.getPayload().getTriggerWord());
                if (handler != null) {
                    return handler.apply(request, request.getContext());
                } else {
                    log.warn("No OutgoingWebhooksHandler for trigger_word: {}", request.getPayload().getTriggerWord());
                }
                break;
            }
            case Event: {
                if (eventsDispatcher.isRunning()) {
                    eventsDispatcher.enqueue(slackRequest.getRequestBodyAsString());
                }
                EventRequest request = (EventRequest) slackRequest;
                BoltEventHandler<Event> handler = eventHandlers.get(request.getEventTypeAndSubtype());
                if (handler != null) {
                    BoltEventPayload payload = buildEventPayload(request);
                    return handler.apply(payload, request.getContext());
                } else {
                    log.warn("No BoltEventHandler registered for event: {}", request.getEventTypeAndSubtype());
                    return Response.ok();
                }
            }
            case UrlVerification: {
                // https://api.slack.com/events/url_verification
                return Response.builder()
                        .statusCode(200)
                        .contentType("text/plain")
                        .body(((UrlVerificationRequest) slackRequest).getChallenge())
                        .build();
            }
            case AttachmentAction: {
                AttachmentActionRequest request = (AttachmentActionRequest) slackRequest;
                String callbackId = request.getPayload().getCallbackId();
                for (Pattern pattern : attachmentActionHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        AttachmentActionHandler handler = attachmentActionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No AttachmentActionHandler registered for callback_id: {}", request.getPayload().getCallbackId());
                break;
            }
            case BlockAction: {
                BlockActionRequest request = (BlockActionRequest) slackRequest;
                List<BlockActionPayload.Action> actions = request.getPayload().getActions();
                if (actions.size() == 1) {
                    String actionId = actions.get(0).getActionId();
                    for (Pattern pattern : blockActionHandlers.keySet()) {
                        if (pattern.matcher(actionId).matches()) {
                            BlockActionHandler handler = blockActionHandlers.get(pattern);
                            return handler.apply(request, request.getContext());
                        }
                    }
                    log.warn("No BlockActionHandler registered for action_id: {}", actions.get(0).getActionId());
                } else {
                    for (BlockActionPayload.Action action : request.getPayload().getActions()) {
                        // Returned response values will be ignored
                        blockActionHandlers.get(action.getActionId());
                    }
                }
                break;
            }
            case BlockSuggestion: {
                BlockSuggestionRequest request = (BlockSuggestionRequest) slackRequest;
                String actionId = request.getPayload().getActionId();
                for (Pattern pattern : blockSuggestionHandlers.keySet()) {
                    if (pattern.matcher(actionId).matches()) {
                        BlockSuggestionHandler handler = blockSuggestionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No BlockSuggestionHandler registered for action_id: {}", actionId);
                break;
            }
            case MessageAction: {
                MessageActionRequest request = (MessageActionRequest) slackRequest;
                String callbackId = request.getPayload().getCallbackId();
                for (Pattern pattern : messageActionHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        MessageActionHandler handler = messageActionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No MessageActionHandler registered for callback_id: {}", request.getPayload().getCallbackId());
                break;
            }
            case DialogSubmission: {
                DialogSubmissionRequest request = (DialogSubmissionRequest) slackRequest;
                String callbackId = request.getPayload().getCallbackId();
                for (Pattern pattern : dialogSubmissionHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        DialogSubmissionHandler handler = dialogSubmissionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No DialogSubmissionHandler registered for callback_id: {}", request.getPayload().getCallbackId());
                break;
            }
            case DialogCancellation: {
                DialogCancellationRequest request = (DialogCancellationRequest) slackRequest;
                String callbackId = request.getPayload().getCallbackId();
                for (Pattern pattern : dialogCancellationHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        DialogCancellationHandler handler = dialogCancellationHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No DialogCancellationHandler registered for callback_id: {}", request.getPayload().getCallbackId());
                break;
            }
            case DialogSuggestion: {
                DialogSuggestionRequest request = (DialogSuggestionRequest) slackRequest;
                String callbackId = request.getPayload().getCallbackId();
                for (Pattern pattern : dialogSuggestionHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        DialogSuggestionHandler handler = dialogSuggestionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No DialogSuggestionHandler registered for callback_id: {}", request.getPayload().getCallbackId());
                break;
            }
            case ViewSubmission: {
                ViewSubmissionRequest request = (ViewSubmissionRequest) slackRequest;
                String callbackId = request.getPayload().getView().getCallbackId();
                for (Pattern pattern : viewSubmissionHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        ViewSubmissionHandler handler = viewSubmissionHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No ViewSubmissionHandler registered for callback_id: {}", request.getPayload().getView().getCallbackId());
                break;
            }
            case ViewClosed: {
                ViewClosedRequest request = (ViewClosedRequest) slackRequest;
                String callbackId = request.getPayload().getView().getCallbackId();
                for (Pattern pattern : viewClosedHandlers.keySet()) {
                    if (pattern.matcher(callbackId).matches()) {
                        ViewClosedHandler handler = viewClosedHandlers.get(pattern);
                        return handler.apply(request, request.getContext());
                    }
                }
                log.warn("No ViewClosedHandler registered for callback_id: {}", request.getPayload().getView().getCallbackId());
                break;
            }
            default:
        }
        return Response.json(404, "{\"error\":\"no handler found\"}");
    }

}
