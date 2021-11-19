package fr.frogdevelopment.jenkins.plugins.mq;

import hudson.Extension;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.amqp.core.MessageBuilder.withBody;

@Extension
public class RabbitMqQueueListener extends QueueListener {

    @Override
    public void onEnterWaiting(Queue.WaitingItem wi) {
        try {
            if (!wi.task.getName().startsWith("JJB_")) {
                return;
            }
            for (RabbitMqBuilder.RabbitConfig rabbitConfig : geDescriptor().getConfigs().getRabbitConfigs()) {
                if (!rabbitConfig.getEnableQueueListener()) {
                    continue;
                }
                CachingConnectionFactory factory = RabbitMqFactory.getCachingConnectionFactory(rabbitConfig);
                RabbitTemplate rabbitTemplate = RabbitMqFactory.getRabbitTemplate(factory);
                rabbitTemplate.send(
                        rabbitConfig.getExchange(),
                        rabbitConfig.getRoutingKey(),
                        withBody(getMessage(wi).getBytes(StandardCharsets.UTF_8)).build());
            }
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }
    }

    public static RabbitMqBuilder.RabbitMqDescriptor geDescriptor() {
        return (RabbitMqBuilder.RabbitMqDescriptor) Jenkins.get().getDescriptor(RabbitMqBuilder.class);
    }

    public String getMessage(Queue.WaitingItem wi) {
        Map<String, Object> msg = new HashMap<>();
        for (ParametersAction pa : wi.getActions(ParametersAction.class)) {
            for (ParameterValue p : pa.getParameters()) {
                if (p.getName().endsWith("Id")) {
                    msg.put(p.getName(), p.getValue());
                }
            }
        }
        String jenkinsRootUrl = Jenkins.get().getRootUrl();
        long queueId = wi.getId();
        msg.put("taskName", wi.task.getName());
        msg.put("status", "inQueue");
        msg.put("queueId", queueId);
        msg.put("url", jenkinsRootUrl + "queue/item/" + queueId + "/api/json?pretty=true");
        return JSONObject.fromObject(msg).toString();
    }
}
