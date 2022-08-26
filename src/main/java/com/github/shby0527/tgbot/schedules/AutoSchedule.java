package com.github.shby0527.tgbot.schedules;

import com.github.shby0527.tgbot.dao.UserJobsMapper;
import com.github.shby0527.tgbot.entities.Userjobs;
import com.github.shby0527.tgbot.services.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AutoSchedule {


    @Autowired
    private UserJobsMapper userJobsMapper;

    @Autowired(required = false)
    private Map<String, SchedulerService> schedulerServiceMap = Collections.emptyMap();


    @Scheduled(cron = "0 */1 * * * ?")
    public void autoSend() {
        if (schedulerServiceMap.isEmpty()) return;
        ZonedDateTime now = ZonedDateTime.now();
        long timestamp = Date.from(now.toInstant()).getTime();
        List<Userjobs> allJobs = userJobsMapper.getAllJobs();
        allJobs.stream()
                .filter(p -> p.getNexttruck() <= timestamp)
                .forEach(p -> {
                    SchedulerService schedulerService = schedulerServiceMap.get(p.getScheduler());
                    if (schedulerService == null) return;
                    long nextTruck = 0;
                    if (CronExpression.isValidExpression(p.getCorn())) {
                        CronExpression expression = CronExpression.parse(p.getCorn());
                        ZonedDateTime next = expression.next(now);
                        if (next == null) return;
                        nextTruck = Date.from(next.toInstant()).getTime() - 10;
                    } else {
                        try {
                            Duration duration = Duration.parse(p.getCorn());
                            nextTruck = timestamp + duration.getSeconds() * 1000 - 10;
                        } catch (Throwable t) {
                            return;
                        }
                    }
                    userJobsMapper.updateNextTruck(p.getId(), nextTruck);
                    schedulerService.scheduler(p.getUserid(), p.getChatid(), p.getArguments());
                });
    }

}
