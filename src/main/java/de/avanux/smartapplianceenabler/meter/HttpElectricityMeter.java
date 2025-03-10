/*
 * Copyright (C) 2017 Axel Müller <axel.mueller@avanux.de>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package de.avanux.smartapplianceenabler.meter;

import de.avanux.smartapplianceenabler.appliance.ApplianceIdConsumer;
import de.avanux.smartapplianceenabler.appliance.ApplianceLifeCycle;
import de.avanux.smartapplianceenabler.configuration.ConfigurationException;
import de.avanux.smartapplianceenabler.http.*;
import de.avanux.smartapplianceenabler.notification.NotificationHandler;
import de.avanux.smartapplianceenabler.notification.NotificationType;
import de.avanux.smartapplianceenabler.notification.NotificationProvider;
import de.avanux.smartapplianceenabler.notification.Notifications;
import de.avanux.smartapplianceenabler.protocol.ContentProtocolHandler;
import de.avanux.smartapplianceenabler.protocol.ContentProtocolType;
import de.avanux.smartapplianceenabler.protocol.JsonContentProtocolHandler;
import de.avanux.smartapplianceenabler.util.ParentWithChild;
import de.avanux.smartapplianceenabler.configuration.Validateable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Electricity meter reading current power and energy from the response of a HTTP request.
 * <p>
 * IMPORTANT: URLs have to be escaped (e.g. use "&amp;" instead of "&")
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class HttpElectricityMeter implements Meter, ApplianceLifeCycle, Validateable, PollPowerExecutor, PollEnergyExecutor,
        ApplianceIdConsumer, NotificationProvider {

    private transient Logger logger = LoggerFactory.getLogger(HttpElectricityMeter.class);
    @XmlAttribute
    private Integer pollInterval; // seconds
    @XmlAttribute
    private String contentProtocol;
    @XmlElement(name = "HttpConfiguration")
    private HttpConfiguration httpConfiguration;
    @XmlElement(name = "HttpRead")
    private List<HttpRead> httpReads;
    @XmlElement(name = "Notifications")
    private Notifications notifications;
    private transient String applianceId;
    private transient HttpTransactionExecutor httpTransactionExecutor = new HttpTransactionExecutor();
    private transient PollPowerMeter pollPowerMeter;
    private transient PollEnergyMeter pollEnergyMeter;
    private transient HttpHandler httpHandler = new HttpHandler();
    private transient ContentProtocolHandler contentContentProtocolHandler;

    @Override
    public void setApplianceId(String applianceId) {
        this.applianceId = applianceId;
        this.httpHandler.setApplianceId(applianceId);
        this.httpTransactionExecutor.setApplianceId(applianceId);
    }

    @Override
    public void setNotificationHandler(NotificationHandler notificationHandler) {
        if(notificationHandler != null) {
            notificationHandler.setRequestedNotifications(notifications);
            this.httpTransactionExecutor.setNotificationHandler(notificationHandler);
        }
    }

    @Override
    public Notifications getNotifications() {
        return notifications;
    }

    public void setHttpTransactionExecutor(HttpTransactionExecutor httpTransactionExecutor) {
        this.httpTransactionExecutor = httpTransactionExecutor;
    }

    public void setHttpConfiguration(HttpConfiguration httpConfiguration) {
        this.httpConfiguration = httpConfiguration;
    }

    public List<HttpRead> getHttpReads() {
        return httpReads;
    }

    public void setHttpReads(List<HttpRead> httpReads) {
        this.httpReads = httpReads;
    }

    public void setContentProtocol(ContentProtocolType contentProtocolType) {
        this.contentProtocol = contentProtocolType != null ? contentProtocolType.name() : null;
    }

    public Integer getPollInterval() {
        return pollInterval;
    }
    public void setPollInterval(Integer pollInterval) {
        if(this.pollInterval == null) {
            this.pollInterval = pollInterval;
        }
    }

    protected PollPowerMeter getPollPowerMeter() {
        return pollPowerMeter;
    }

    public void setPollPowerMeter(PollPowerMeter pollPowerMeter) {
        this.pollPowerMeter = pollPowerMeter;
    }

    protected PollEnergyMeter getPollEnergyMeter() {
        return pollEnergyMeter;
    }

    public void setPollEnergyMeter(PollEnergyMeter pollEnergyMeter) {
        this.pollEnergyMeter = pollEnergyMeter;
    }

    @Override
    public void validate() throws ConfigurationException {
        logger.debug("{}: Validating configuration", applianceId);
        logger.debug("{}: configured: poll interval={}s", applianceId, getPollInterval());
        HttpValidator validator = new HttpValidator(applianceId);

        // Meter should meter either Power or Energy or both
        boolean powerValid = validator.validateReads(
                Collections.singletonList(MeterValueName.Power.name()), this.httpReads, false);
        boolean energyValid = validator.validateReads(
                Collections.singletonList(MeterValueName.Energy.name()), this.httpReads, false);
        if(! (powerValid || energyValid)) {
            logger.error("{}: Configuration missing for either {} or {}",
                    applianceId, MeterValueName.Power.name(), MeterValueName.Energy.name());
            throw new ConfigurationException();
        }
    }

    @Override
    public void init() {
        if(HttpRead.getFirstHttpRead(MeterValueName.Power.name(), this.httpReads) != null) {
            pollPowerMeter = new PollPowerMeter();
            pollPowerMeter.setApplianceId(applianceId);
        }
        if(HttpRead.getFirstHttpRead(MeterValueName.Energy.name(), this.httpReads) != null) {
            pollEnergyMeter = new PollEnergyMeter();
            pollEnergyMeter.setApplianceId(applianceId);
        }
        if(this.httpConfiguration != null) {
            this.httpTransactionExecutor.setConfiguration(this.httpConfiguration);
        }
        this.httpHandler.setHttpTransactionExecutor(httpTransactionExecutor);
    }

    @Override
    public void start(LocalDateTime now, Timer timer) {
        logger.debug("{}: Starting ...", applianceId);
        if(pollPowerMeter != null) {
            pollPowerMeter.start(timer, getPollInterval(), this);
        }
        if(pollEnergyMeter != null) {
            pollEnergyMeter.start(timer, this);
        }
    }

    @Override
    public void stop(LocalDateTime now) {
        logger.debug("{}: Stopping ...", applianceId);
        if(pollEnergyMeter != null) {
            pollEnergyMeter.cancelTimer();
        }
        if(pollPowerMeter != null) {
            pollPowerMeter.cancelTimer();
        }
    }

    @Override
    public void addPowerUpdateListener(PowerUpdateListener listener) {
        if(pollPowerMeter != null) {
            this.pollPowerMeter.addPowerUpateListener(listener);
        }
        if(pollEnergyMeter != null) {
            this.pollEnergyMeter.addPowerUpateListener(listener);
        }
    }

    @Override
    public int getAveragePower() {
        int power = 0;
        if(pollEnergyMeter != null) {
            power = pollEnergyMeter.getAveragePower();
        }
        else if(pollPowerMeter != null) {
            power = pollPowerMeter.getAveragePower(LocalDateTime.now());
        }
        logger.debug("{}: average power = {}W", applianceId, power);
        return power;
    }

    @Override
    public int getMinPower() {
        int power = 0;
        if(pollEnergyMeter != null) {
            power = pollEnergyMeter.getAveragePower();
        }
        else if(pollPowerMeter != null) {
            power = pollPowerMeter.getMinPower(LocalDateTime.now());
        }
        logger.debug("{}: min power = {}W", applianceId, power);
        return power;
    }

    @Override
    public int getMaxPower() {
        int power = 0;
        if(pollEnergyMeter != null) {
            power = pollEnergyMeter.getAveragePower();
        }
        else if(pollPowerMeter != null) {
            power = pollPowerMeter.getMaxPower(LocalDateTime.now());
        }
        logger.debug("{}: max power = {}W", applianceId, power);
        return power;
    }

    @Override
    public Double pollPower() {
        ParentWithChild<HttpRead, HttpReadValue> powerRead = HttpRead.getFirstHttpRead(MeterValueName.Power.name(), this.httpReads);
        return getValue(powerRead);
    }

    @Override
    public float getEnergy() {
        return pollEnergyMeter != null ? (float) this.pollEnergyMeter.getEnergy() : 0.0f;
    }

    @Override
    public void startEnergyMeter() {
        if(pollEnergyMeter != null) {
            logger.debug("{}: Start energy meter ...", applianceId);
            Double energy = this.pollEnergyMeter.startEnergyCounter();
            logger.debug("{}: Current energy meter value: {} kWh", applianceId, energy);
        }
    }

    @Override
    public void stopEnergyMeter() {
        if(pollEnergyMeter != null) {
            logger.debug("{}: Stop energy meter ...", applianceId);
            Double energy = this.pollEnergyMeter.stopEnergyCounter();
            logger.debug("{}: Current energy meter value: {} kWh", applianceId, energy);
        }
    }

    @Override
    public void resetEnergyMeter() {
        logger.debug("{}: Reset energy meter ...", applianceId);
        if(pollEnergyMeter != null) {
            this.pollEnergyMeter.reset();
        }
    }

    @Override
    public Double pollEnergy(LocalDateTime now) {
        return pollEnergy();
    }

    protected double pollEnergy() {
        ParentWithChild<HttpRead, HttpReadValue> energyRead = HttpRead.getFirstHttpRead(MeterValueName.Energy.name(), this.httpReads);
        return getValue(energyRead);
    }

    private double getValue(ParentWithChild<HttpRead, HttpReadValue> read) {
        return this.httpHandler.getDoubleValue(read, getContentContentProtocolHandler());
    }

    public ContentProtocolHandler getContentContentProtocolHandler() {
        if(this.contentContentProtocolHandler == null) {
            if(ContentProtocolType.JSON.name().equals(this.contentProtocol)) {
                this.contentContentProtocolHandler = new JsonContentProtocolHandler();
            }
        }
        return this.contentContentProtocolHandler;
    }
}
