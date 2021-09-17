package fr.frogdevelopment.jenkins.plugins.mq;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import jenkins.model.Jenkins;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.amqp.core.MessageBuilder.withBody;

@Extension
public class RabbitMqQueueListener extends QueueListener {

    @Override
    public void onEnterWaiting(Queue.WaitingItem wi) {
        try {
            Map msg  = new HashMap<String, String>();
            msg.put("queueId", wi.getId());
            msg.put("url", Jenkins.get().getRootUrl());

            RabbitMqBuilder.RabbitConfig rConfig = new RabbitMqBuilder.RabbitConfig("rabbitmq", "192.168.48.2", 5672, "admin", "root-123456", false, "/");
            CachingConnectionFactory factory = RabbitMqFactory.getCachingConnectionFactory(rConfig);
            RabbitTemplate rabbitTemplate = RabbitMqFactory.getRabbitTemplate(factory);
            rabbitTemplate.send("flow_tasks_results", "started_live", withBody(msg.toString().getBytes(Charset.forName("UTF-8"))).build());
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }

    }
}
