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
import de.avanux.smartapplianceenabler.configuration.ConfigurationException;
import de.avanux.smartapplianceenabler.configuration.Validateable;
import de.avanux.smartapplianceenabler.modbus.*;
import de.avanux.smartapplianceenabler.modbus.executor.ModbusExecutorFactory;
import de.avanux.smartapplianceenabler.modbus.executor.ModbusReadTransactionExecutor;
import de.avanux.smartapplianceenabler.notification.NotificationHandler;
import de.avanux.smartapplianceenabler.notification.NotificationProvider;
import de.avanux.smartapplianceenabler.notification.NotificationType;
import de.avanux.smartapplianceenabler.notification.Notifications;
import de.avanux.smartapplianceenabler.util.ParentWithChild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Timer;

/**
 * Represents a ModBus electricity meter device accessible by ModBus TCP.
 * The device is polled according to poll interval in order to provide min/avg/max values of the averaging interval.
 * The TCP connection to the device remains established across the polls.
 */
public class ModbusElectricityMeter extends ModbusSlave implements Meter, ApplianceIdConsumer,
        Validateable, PollPowerExecutor, PollEnergyExecutor, NotificationProvider {

    private transient Logger logger = LoggerFactory.getLogger(ModbusElectricityMeter.class);
    @XmlElement(name = "ModbusRead")
    private List<ModbusRead> modbusReads;
    @XmlAttribute
    private Integer pollInterval; // seconds
    @XmlElement(name = "Notifications")
    private Notifications notifications;
    private transient PollPowerMeter pollPowerMeter;
    private transient PollEnergyMeter pollEnergyMeter;
    private transient NotificationHandler notificationHandler;

    @Override
    public void setApplianceId(String applianceId) {
        super.setApplianceId(applianceId);
    }

    @Override
    public void setNotificationHandler(NotificationHandler notificationHandler) {
        this.notificationHandler = notificationHandler;
        if(this.notificationHandler != null) {
            this.notificationHandler.setRequestedNotifications(notifications);
        }
    }

    @Override
    public Notifications getNotifications() {
        return notifications;
    }

    public List<ModbusRead> getModbusReads() {
        return modbusReads;
    }

    public void setModbusReads(List<ModbusRead> modbusReads) {
        this.modbusReads = modbusReads;
    }

    public Integer getPollInterval() {
        return pollInterval;
    }
    public void setPollInterval(Integer pollInterval) {
        if(this.pollInterval == null) {
            this.pollInterval = pollInterval;
        }
    }

    @Override
    public void validate() throws ConfigurationException {
        logger.debug("{}: Validating configuration", getApplianceId());
        logger.debug("{}: configured: poll interval={}s", getApplianceId(), getPollInterval());
        ModbusValidator validator = new ModbusValidator(getApplianceId());

        MeterValueName power = MeterValueName.Power;
        ParentWithChild<ModbusRead, ModbusReadValue> powerRead
                = ModbusRead.getFirstRegisterRead(power.name(), modbusReads);
        boolean powerReadValid = false;
        if(powerRead != null) {
            powerReadValid = validator.validateReads(power.name(), Collections.singletonList(powerRead));
        }

        MeterValueName energy = MeterValueName.Energy;
        ParentWithChild<ModbusRead, ModbusReadValue> energyRead
                = ModbusRead.getFirstRegisterRead(energy.name(), modbusReads);
        boolean energyReadValid = false;
        if(energyRead != null) {
            energyReadValid = validator.validateReads(energy.name(), Collections.singletonList(energyRead));
        }

        if(! (powerReadValid || energyReadValid)) {
            logger.error("{}: Configuration missing for either {} or {}",
                    getApplianceId(), MeterValueName.Power.name(), MeterValueName.Energy.name());
            throw new ConfigurationException();
        }
    }

    @Override
    public void init() {
        if(ModbusRead.getFirstRegisterRead(MeterValueName.Power.name(), modbusReads) != null) {
            this.pollPowerMeter = new PollPowerMeter();
            this.pollPowerMeter.setApplianceId(getApplianceId());
        }
        if(ModbusRead.getFirstRegisterRead(MeterValueName.Energy.name(), modbusReads) != null) {
            this.pollEnergyMeter = new PollEnergyMeter();
            this.pollEnergyMeter.setApplianceId(getApplianceId());
        }
    }

    @Override
    public void start(LocalDateTime now, Timer timer) {
        logger.debug("{}: Starting ...", getApplianceId());
        if(pollPowerMeter != null) {
            pollPowerMeter.start(timer, getPollInterval(), this);
        }
        if(pollEnergyMeter != null) {
            pollEnergyMeter.start(timer, this);
        }
    }

    @Override
    public void stop(LocalDateTime now) {
        logger.debug("{}: Stopping ...", getApplianceId());
        if(pollPowerMeter != null) {
            pollPowerMeter.cancelTimer();
        }
        if(pollEnergyMeter != null) {
            pollEnergyMeter.cancelTimer();
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
        logger.debug("{}: average power = {}W", getApplianceId(), power);
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
        logger.debug("{}: min power = {}W", getApplianceId(), power);
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
        logger.debug("{}: max power = {}W", getApplianceId(), power);
        return power;
    }


    @Override
    public Double pollPower() {
        ParentWithChild<ModbusRead, ModbusReadValue> read
                = ModbusRead.getFirstRegisterRead(MeterValueName.Power.name(), modbusReads);
        return readRegister(read.parent());
    }

    @Override
    public float getEnergy() {
        return pollEnergyMeter != null ? (float) this.pollEnergyMeter.getEnergy() : 0.0f;
    }

    @Override
    public void startEnergyMeter() {
        logger.debug("{}: Start energy meter ...", getApplianceId());
        this.pollEnergyMeter.startEnergyCounter();
    }

    @Override
    public void stopEnergyMeter() {
        logger.debug("{}: Stop energy meter ...", getApplianceId());
        this.pollEnergyMeter.stopEnergyCounter();
    }

    @Override
    public void resetEnergyMeter() {
        logger.debug("{}: Reset energy meter ...", getApplianceId());
        this.pollEnergyMeter.reset();
    }

    @Override
    public void addPowerUpdateListener(PowerUpdateListener listener) {
        if(pollEnergyMeter != null) {
            this.pollPowerMeter.addPowerUpateListener(listener);
        }
        if(pollEnergyMeter != null) {
            this.pollEnergyMeter.addPowerUpateListener(listener);
        }
    }

    @Override
    public Double pollEnergy(LocalDateTime now) {
        ParentWithChild<ModbusRead, ModbusReadValue> read
                = ModbusRead.getFirstRegisterRead(MeterValueName.Energy.name(), modbusReads);
        return readRegister(read.parent());
    }

    private double readRegister(ModbusRead registerRead) {
        try {
            ModbusReadTransactionExecutor executor = ModbusExecutorFactory.getReadExecutor(getApplianceId(),
                    registerRead.getAddress(), registerRead.getType(), registerRead.getValueType(), registerRead.getWords(),
                    registerRead.getByteOrder(), registerRead.getFactorToValue());
            if(executor != null) {
                executeTransaction(executor, true);
                Object registerValue = executor.getValueTransformer().getValue();
                if(registerValue instanceof Double) {
                    return (Double) registerValue;
                }
            }
            else {
                logger.error("{}: No executor found", getApplianceId());
            }
        }
        catch(Exception e) {
            logger.error("{}: Error reading input register {}", getApplianceId(), registerRead.getAddress(), e);
            if(this.notificationHandler != null) {
                this.notificationHandler.sendNotification(NotificationType.COMMUNICATION_ERROR);
            }
        }
        return 0;
    }
}
