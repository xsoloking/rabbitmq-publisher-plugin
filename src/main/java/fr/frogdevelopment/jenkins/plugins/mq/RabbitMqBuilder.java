package fr.frogdevelopment.jenkins.plugins.mq;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.springframework.amqp.core.MessageBuilder.withBody;

// cf example https://github.com/jenkinsci/hello-world-plugin
@SuppressFBWarnings({"WeakerAccess", "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE"})
public class RabbitMqBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqBuilder.class);
    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private final String rabbitName;
    private final String exchange;
    private String routingKey;
    private final String data;
    private boolean toJson;
    private boolean conversion = true;

    @Deprecated
    public RabbitMqBuilder(String rabbitName, String exchange, String routingKey, String data, boolean toJson) {
        this.rabbitName = rabbitName;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.data = data;
        this.toJson = toJson;
    }

    @DataBoundConstructor
    public RabbitMqBuilder(String rabbitName, String exchange, String data) {
        this.rabbitName = rabbitName;
        this.exchange = exchange;
        this.data = data;
    }

    public String getRabbitName() {
        return rabbitName;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    @DataBoundSetter
    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getData() {
        return data;
    }

    public boolean isToJson() {
        return toJson;
    }

    @DataBoundSetter
    public void setToJson(boolean toJson) {
        this.toJson = toJson;
    }

    public boolean getConversion() {
        return conversion;
    }

    @DataBoundSetter
    public void setConversion(boolean conversion) {
        this.conversion = conversion;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        listener.getLogger().println("Retrieving parameters");
        LOGGER.info("Retrieving parameters :");

        //noinspection unchecked
        Map<String, String> buildVariables = build.getBuildVariables();
        LOGGER.debug("BuildVariables : {}", buildVariables);

        Map<String, String> buildParameters = new HashMap<>(buildVariables);

        Cause.UserIdCause userIdCause = (Cause.UserIdCause) build.getCause(Cause.UserIdCause.class);
        if (userIdCause != null) {
            buildParameters.put("BUILD_USER_ID", userIdCause.getUserId());
            buildParameters.put("BUILD_USER_NAME", userIdCause.getUserName());
        }

        LOGGER.debug("Parameters retrieved : {}", buildParameters);

        EnvVars env = new EnvVars();
        try {
            env = build.getEnvironment(listener);
        } catch (Exception e) {
            // nothing to do ?
        }

        LOGGER.debug("Environmental variables : {}", env);

        return perform(buildParameters, env, listener);
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) {
        listener.getLogger().println("Retrieving data");
        LOGGER.info("Retrieving data :");

        Map<String, String> buildParameters = new HashMap<>();
        // FIXME https://jenkins.io/doc/developer/plugin-development/pipeline-integration/

        EnvVars env = new EnvVars();

        LOGGER.debug("Data retrieved : {}", buildParameters);

        perform(buildParameters, env, listener);
    }

    private boolean perform(@Nonnull Map<String, String> buildParameters, @Nonnull EnvVars env,
                            @Nonnull TaskListener listener) {
        PrintStream console = listener.getLogger();

        try {
            console.println("Initialisation Rabbit-MQ");
            // INIT RABBIT-MQ
            RabbitConfig rabbitConfig = getDescriptor().getRabbitConfig(rabbitName);

            if (rabbitConfig == null) {
                throw new IllegalArgumentException("Unknown rabbit config : " + rabbitName);
            }

            console.println("Building message");

            String expandedData = env.expand(data);
            String message;
            if (toJson) {
                message = Utils.getJsonMessage(buildParameters, expandedData);
                LOGGER.info("Sending message as JSON:\n{}", message);
                console.println("Sending message as JSON:\n" + message);
            } else {
                message = Utils.getRawMessage(buildParameters, expandedData);
                LOGGER.info("Sending raw message:\n{}", message);
                console.println("Sending raw message:\n" + message);
            }

            console.println("Sending message");

            CachingConnectionFactory factory = RabbitMqFactory.getCachingConnectionFactory(rabbitConfig);
            RabbitTemplate rabbitTemplate = RabbitMqFactory.getRabbitTemplate(factory);
            if (conversion) {
                rabbitTemplate.convertAndSend(exchange, routingKey, message);
            } else {
                rabbitTemplate.send(exchange, routingKey, withBody(message.getBytes(DEFAULT_CHARSET)).build());
            }
            factory.destroy();

            console.println("Connection destroyed");
        } catch (Exception e) {
            LOGGER.error("Error while sending to Rabbit-MQ", e);
            console.println("Error while sending to Rabbit-MQ : " + ExceptionUtils.getMessage(e));

            return false;
        }

        return true;
    }

    @Override
    public RabbitMqDescriptor getDescriptor() {
        return (RabbitMqDescriptor) super.getDescriptor();
    }

    @Extension
    @Symbol("rabbitMQPublisher")
    public static class RabbitMqDescriptor extends BuildStepDescriptor<Builder> {

        private Configs configs;

        public RabbitMqDescriptor() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish to Rabbit-MQ";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) {
            this.configs = Configs.fromJSON(json);

            save();

            return true;
        }

        public Configs getConfigs() {
            return configs;
        }

        public void setConfigs(Configs configs) {
            this.configs = configs;
        }

        public RabbitConfig getRabbitConfig(String configName) {
            return configs.getRabbitConfigs()
                    .stream()
                    .filter(rc -> rc.getName().equals(configName))
                    .findFirst()
                    .orElse(null);
        }

        public ListBoxModel doFillRabbitNameItems() {
            ListBoxModel options = new ListBoxModel();
            configs.rabbitConfigs.forEach(rc -> options.add(rc.name));
            return options;
        }

        public FormValidation doCheckParameters(@QueryParameter String parameters) {
            if (StringUtils.isBlank(parameters)) {
                return FormValidation.error("Parameters required");
            } else {
                String[] lines = parameters.split("\\r?\\n");
                for (String line : lines) {
                    String[] splitLine = line.split("=");
                    if (splitLine.length == 2) {
                        if (StringUtils.isBlank(splitLine[0])) {
                            return FormValidation.error("Empty routingKey for : [%s]", line);
                        }
                    } else {
                        return FormValidation
                                .error("Incorrect data format for value [%s]. Expected format is routingKey=value",
                                        line);
                    }
                }

                return FormValidation.ok();
            }
        }
    }

    public static final class Configs extends AbstractDescribableImpl<Configs> {

        private final List<RabbitConfig> rabbitConfigs;

        @DataBoundConstructor
        public Configs(List<RabbitConfig> rabbitConfigs) {
            this.rabbitConfigs = rabbitConfigs != null ? new ArrayList<>(rabbitConfigs) : Collections.emptyList();
        }

        @Override
        public ConfigsDescriptor getDescriptor() {
            return (ConfigsDescriptor) super.getDescriptor();
        }

        public List<RabbitConfig> getRabbitConfigs() {
            return Collections.unmodifiableList(rabbitConfigs);
        }

        static Configs fromJSON(JSONObject jsonObject) {
            if (!jsonObject.containsKey("configs")) {
                return null;
            }

            List<RabbitConfig> rabbitConfigs = new ArrayList<>();

            JSONObject configsJSON = jsonObject.getJSONObject("configs");

            JSONObject rabbitConfigsJSON = configsJSON.optJSONObject("rabbitConfigs");
            if (rabbitConfigsJSON != null) {
                rabbitConfigs.add(RabbitConfig.fromJSON(rabbitConfigsJSON));
            }

            JSONArray rabbitConfigsJSONArray = configsJSON.optJSONArray("rabbitConfigs");
            if (rabbitConfigsJSONArray != null) {
                for (int i = 0; i < rabbitConfigsJSONArray.size(); i++) {
                    rabbitConfigs.add(RabbitConfig.fromJSON(rabbitConfigsJSONArray.getJSONObject(i)));
                }
            }

            return new Configs(rabbitConfigs);
        }

        @Extension
        public static class ConfigsDescriptor extends Descriptor<Configs> {

        }
    }

    public static class RabbitConfig extends AbstractDescribableImpl<RabbitConfig> {

        private String name;
        private String host;
        private int port;
        private boolean isSecure;
        private String username;
        private String password;
        private String virtualHost;
        private boolean enableQueueListener;
        private boolean enableRunListener;
        private String exchange;
        private String routingKey;

        @DataBoundConstructor
        public RabbitConfig(String name, String host, int port, String username, String password, boolean isSecure,
                            String virtualHost, boolean enableQueueListener, boolean enableRunListener,
                            String exchange, String routingKey) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.isSecure = isSecure;
            this.username = username;
            this.password = password;
            this.virtualHost = virtualHost;
            this.enableQueueListener = enableQueueListener;
            this.enableRunListener = enableRunListener;
            this.exchange = exchange;
            this.routingKey = routingKey;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getVirtualHost() {
            return virtualHost;
        }

        public String getExchange() {
            return exchange;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public boolean getEnableQueueListener() {
            return enableQueueListener;
        }

        public boolean getEnableRunListener() {
            return enableRunListener;
        }

        public static String getDecodedPassword(String password) {
            Secret decrypt = Secret.decrypt(password);

            if (decrypt == null) {
                return password;
            }

            return decrypt.getPlainText();
        }

        public String getDecodedPassword() {
            return getDecodedPassword(password);
        }

        public boolean getIsSecure() {
            return isSecure;
        }

        static RabbitConfig fromJSON(JSONObject jsonObject) {
            String name = jsonObject.getString("name");
            String host = jsonObject.getString("host");
            int port = jsonObject.getInt("port");
            String username = jsonObject.getString("username");
            String password = jsonObject.getString("password");
            boolean isSecure = jsonObject.getBoolean("isSecure");
            String virtualHost = jsonObject.getString("virtualHost");
            String exchange = jsonObject.getString("exchange");
            boolean enableQueueListener = jsonObject.getBoolean("enableQueueListener");
            boolean enableRunListener = jsonObject.getBoolean("enableRunListener");
            String routingKey = jsonObject.getString("routingKey");
            Secret secret = Secret.fromString(password);

            return new RabbitConfig(name, host, port, username, secret.getEncryptedValue(), isSecure, virtualHost,
                    enableQueueListener, enableRunListener, exchange, routingKey);
        }

        @Override
        public RabbitConfigDescriptor getDescriptor() {
            return (RabbitConfigDescriptor) super.getDescriptor();
        }

        @Extension
        public static class RabbitConfigDescriptor extends Descriptor<RabbitConfig> {

            public FormValidation doCheckPort(@QueryParameter String value) {
                if (NumberUtils.isNumber(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error("Not a number");
                }
            }

            @RequirePOST
            public FormValidation doTestConnection(@QueryParameter("host") final String host,
                                                   @QueryParameter("port") final String port,
                                                   @QueryParameter("username") final String username,
                                                   @QueryParameter("password") final String password,
                                                   @QueryParameter("isSecure") final String isSecure,
                                                   @QueryParameter("virtualHost") final String virtualHost) {
                // https://jenkins.io/doc/developer/security/form-validation/
                Jenkins.getActiveInstance().checkPermission(Jenkins.ADMINISTER); // Keep this deprecated method for compatibility with old jenkins

                try {
                    ConnectionFactory connectionFactory = RabbitMqFactory.createConnectionFactory(
                            username,
                            getDecodedPassword(password),
                            host,
                            Integer.parseInt(port),
                            Boolean.parseBoolean(isSecure),
                            virtualHost
                    );

                    try (Connection connection = connectionFactory.newConnection()) {
                        if (connection.isOpen()) {
                            return FormValidation.ok("Connection success");
                        } else {
                            return FormValidation.error("Connection failed");
                        }
                    }
                } catch (IOException | TimeoutException | GeneralSecurityException e) {
                    LOGGER.error("Connection error", e);
                    return FormValidation.error("Client error : " + e.getMessage());
                }
            }
        }
    }

}
