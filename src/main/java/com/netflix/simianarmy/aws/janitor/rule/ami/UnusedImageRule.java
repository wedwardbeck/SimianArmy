/*
 *
 *  Copyright 2012 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.simianarmy.aws.janitor.rule.ami;

import com.netflix.simianarmy.MonkeyCalendar;
import com.netflix.simianarmy.Resource;
import com.netflix.simianarmy.aws.AWSResource;
import com.netflix.simianarmy.aws.janitor.crawler.edda.EddaImageJanitorCrawler;
import com.netflix.simianarmy.janitor.Rule;
import org.apache.commons.lang.Validate;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * The rule class to clean up images that are not used.
 */
public class UnusedImageRule  implements Rule {
    /** The Constant LOGGER. */
    private static final Logger LOGGER = LoggerFactory.getLogger(UnusedImageRule.class);

    private final MonkeyCalendar calendar;
    private final int retentionDays;
    private final int lastReferenceDaysThreshold;

    /**
     * Constructor.
     *
     * @param calendar
     *            The calendar used to calculate the termination time
     * @param retentionDays
     *            The number of days that the marked ASG is retained before being terminated
     * @param lastReferenceDaysThreshold
     *            The number of days that the image has been not referenced that makes the ASG be
     *            considered obsolete
     */
    public UnusedImageRule(MonkeyCalendar calendar, int retentionDays, int lastReferenceDaysThreshold) {
        Validate.notNull(calendar);
        Validate.isTrue(retentionDays >= 0);
        Validate.isTrue(lastReferenceDaysThreshold >= 0);
        this.calendar = calendar;
        this.retentionDays = retentionDays;
        this.lastReferenceDaysThreshold = lastReferenceDaysThreshold;
    }

    @Override
    public boolean isValid(Resource resource) {
        Validate.notNull(resource);
        if (!"IMAGE".equals(resource.getResourceType().name())) {
            return true;
        }
        if (!"available".equals(((AWSResource) resource).getAWSResourceState())) {
            return true;
        }

        if ("true".equals(resource.getAdditionalField(EddaImageJanitorCrawler.AMI_FIELD_BASE_IMAGE))) {
            LOGGER.info(String.format("Image %s is a base image that is used to create other images",
                    resource.getId()));
            return true;
        }
        String instanceRefTime = resource.getAdditionalField(EddaImageJanitorCrawler.AMI_FIELD_LAST_INSTANCE_REF_TIME);
        String lcRefTime = resource.getAdditionalField(EddaImageJanitorCrawler.AMI_FIELD_LAST_LC_REF_TIME);
        Date now = calendar.now().getTime();
        long windowStart = new DateTime(now.getTime()).minusDays(lastReferenceDaysThreshold).getMillis();
        boolean instanceOld = instanceRefTime != null && Long.parseLong(instanceRefTime) < windowStart;
        boolean lcOld = lcRefTime != null && Long.parseLong(lcRefTime) < windowStart;
        if (instanceRefTime == null && lcOld || lcRefTime == null && instanceOld || lcOld && instanceOld) {
            if (resource.getExpectedTerminationTime() == null) {
                Date terminationTime = calendar.getBusinessDay(now, retentionDays);
                resource.setExpectedTerminationTime(terminationTime);
                resource.setTerminationReason(String.format("Image not referenced for %d days",
                        lastReferenceDaysThreshold + retentionDays));
                LOGGER.info(String.format(
                        "Image %s in region %s is marked to be cleaned at %s as it is not referenced"
                               + "for more than %d days",
                        resource.getId(), resource.getRegion(), resource.getExpectedTerminationTime(),
                        lastReferenceDaysThreshold));
            } else {
                LOGGER.info(String.format("Resource %s is already marked.", resource.getId()));
            }
            return false;
        }
        return true;
    }
}
