package fr.frogdevelopment.jenkins.plugins.mq;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import hudson.Extension;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;


import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.amqp.core.MessageBuilder.withBody;


@Extension
public class RabbitMQRunListener extends RunListener<Run<?, ?>> {

    private static final String BUILD_FAILURE_URL_TO_APPEND = "/api/json?depth=2&tree=actions[foundFailureCauses[categories,description,id,name]]";
    public static final String APPLICATION_JSON = "application/json";
    public static final String ACCEPT = "accept";
    public static final String CONTENT_TYPE = "Content-Type";

    @Override
    public void onFinalized(Run<?, ?> r) {
        String jobName = r.getParent().getName();
        if (jobName.startsWith("JJB_") && r.getResult() == Result.FAILURE) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("taskName", jobName);
            msg.put("url", Jenkins.get().getRootUrl() + r.getUrl());
            msg.put("status", "FAILURE");
            msg.put("taskJobBuildId", r.getId());
            msg.put("failureCause", "");
            for (ParametersAction pa : r.getActions(ParametersAction.class)) {
                for (ParameterValue p : pa.getParameters()) {
                    if (p.getName().endsWith("Id")) {
                        msg.put(p.getName(), p.getValue());
                    }
                }
            }
            sendMessage(new JSONObject(msg).toString());
        }
    }

    public static RabbitMqBuilder.RabbitMqDescriptor geDescriptor() {
        return (RabbitMqBuilder.RabbitMqDescriptor) Jenkins.get().getDescriptor(RabbitMqBuilder.class);
    }

    private void sendMessage(String msg) {
        try {
            for (RabbitMqBuilder.RabbitConfig rabbitConfig : geDescriptor().getConfigs().getRabbitConfigs()) {
                if (!rabbitConfig.getEnableRunListener()) {
                    continue;
                }
                CachingConnectionFactory factory = RabbitMqFactory.getCachingConnectionFactory(rabbitConfig);
                RabbitTemplate rabbitTemplate = RabbitMqFactory.getRabbitTemplate(factory);
                rabbitTemplate.send(
                        rabbitConfig.getExchange(),
                        rabbitConfig.getRoutingKey(),
                        withBody(msg.getBytes(StandardCharsets.UTF_8)).build());
            }
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }
    }

    private ArrayList<String> getBuildFailureCauses(Run<?, ?> r) {
        ArrayList<String> finalCase = new ArrayList<>();
        JSONObject response = getJson(Jenkins.get().getRootUrl() + r.getUrl()+ BUILD_FAILURE_URL_TO_APPEND);
        if (response != null && response.has("actions")) {
            JSONArray actions = response.getJSONArray("actions");
            for (int i = 0; i < actions.length(); i++) {
                JSONObject failureResponse = actions.getJSONObject(i);
                if (!failureResponse.keySet().isEmpty()) {
                    if (failureResponse.has("foundFailureCauses")) {
                        JSONArray foundFailureCauses = failureResponse.getJSONArray("foundFailureCauses");
                        for (int j = 0; j < foundFailureCauses.length(); j++) {
                            JSONObject cause = foundFailureCauses.getJSONObject(j);
                            finalCase.add(cause.getString("name") + ": " + cause.getString("description"));
                        }
                        break;
                    }
                }
            }
        }
        return finalCase;
    }

    private JSONObject getJson(String url) {
        try{
            HttpResponse<JsonNode> response = Unirest.get(url)
                    .header(ACCEPT, APPLICATION_JSON)
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .asJson();
            return response.getBody().getObject();
        } catch (UnirestException e){
            e.printStackTrace();
        }
        return null;
    }
}
