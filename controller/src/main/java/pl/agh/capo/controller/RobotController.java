package pl.agh.capo.controller;

import com.vividsolutions.jts.math.Vector2D;
import org.apache.log4j.Logger;
import pl.agh.capo.utilities.EnvironmentalConfiguration;
import pl.agh.capo.utilities.communication.StateCollector;
import pl.agh.capo.utilities.communication.StatePublisher;
import pl.agh.capo.utilities.maze.MazeMap;
import pl.agh.capo.utilities.state.Destination;
import pl.agh.capo.utilities.state.Location;
import pl.agh.capo.utilities.state.State;
import pl.agh.capo.utilities.state.Velocity;
import pl.agh.capo.controller.collision.CollisionFreeVelocityGenerator;
import pl.agh.capo.controller.collision.WallCollisionDetector;
import pl.agh.capo.controller.collision.velocity.AbstractCollisionFreeVelocity;
import pl.agh.capo.controller.collision.velocity.CollisionFreeVelocityType;
import pl.agh.capo.utilities.RobotMotionModel;
import pl.agh.capo.robot.IRobot;
import pl.agh.capo.robot.IRobotManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RobotController implements Runnable {

    private final Logger logger = Logger.getLogger(RobotController.class);

    public static final int MOVE_ROBOT_PERIOD_IN_MS = 200;
    private static final int MONITOR_SENSOR_PERIOD_IN_MS = (int) (MOVE_ROBOT_PERIOD_IN_MS * 1.5);

    private final IRobot robot;
    private final RobotMotionModel motionModel;
    private final StatePublisher statePublisher;
    private final StateCollector stateCollector;
    private final CollisionFreeVelocityGenerator collisionFreeVelocityGenerator;
    private final WallCollisionDetector wallCollisionDetector;
    private final ScheduledExecutorService controlScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService sensorMonitor = Executors.newScheduledThreadPool(1);

    private final int robotId;
    private final IRobotManager manager;

    private List<Destination> path;
    private Destination destination;
    private Destination lastReachDestination;
    private int sensorReadCounter = 0;

    private boolean isSensorWorking = true;

    public RobotController(int robotId, List<Destination> destinationList, MazeMap mazeMap, IRobot robot, IRobotManager manager, CollisionFreeVelocityType collisionFreeVelocityType) {
        this.robotId = robotId;
        this.robot = robot;
        this.manager = manager;
        motionModel = new RobotMotionModel(EnvironmentalConfiguration.ROBOT_MAX_SPEED);

        wallCollisionDetector = new WallCollisionDetector(mazeMap);
        collisionFreeVelocityGenerator = new CollisionFreeVelocityGenerator(collisionFreeVelocityType, robotId, wallCollisionDetector);
        stateCollector = StateCollector.createAndEstablishConnection(collisionFreeVelocityGenerator);
        statePublisher = StatePublisher.createAndEstablishConnection();
        setPath(destinationList);
    }

    public void setPath(List<Destination> destinationList) {
        path = destinationList;
        lastReachDestination = path.remove(0);
        destination = path.get(0);
    }

    public void run() {
        controlScheduler.scheduleAtFixedRate(
                this::controlRobot,
                MOVE_ROBOT_PERIOD_IN_MS,
                MOVE_ROBOT_PERIOD_IN_MS,
                TimeUnit.MILLISECONDS);
        sensorMonitor.scheduleAtFixedRate(
                this::monitorSensor,
                MONITOR_SENSOR_PERIOD_IN_MS,
                MONITOR_SENSOR_PERIOD_IN_MS,
                TimeUnit.MILLISECONDS);
    }

    private void controlRobot() {
        sensorReadCounter++;
        Location robotLocation = robot.getRobotLocation();
        if (robotLocation == null) {
            robot.setVelocity(0.0, 0.0);
            return;
        }
        motionModel.setLocation(robotLocation);
        double destinationDistance = destination.distance(motionModel.getLocation());
        if (!findDestination(destinationDistance)) {
            stop();
            return;
        }
        Velocity optimalVelocity = findOptimalToDestinationVelocity();
        setToDestinationVelocity(optimalVelocity, destinationDistance);

        AbstractCollisionFreeVelocity collisionFreeVelocity = collisionFreeVelocityGenerator
                .createCollisionFreeState(motionModel.getLocation(), optimalVelocity);
        boolean collide = !collisionFreeVelocity.isCurrentVelocityCollisionFree();
        if (collide) {
            optimalVelocity = collisionFreeVelocity.get();
//            System.out.println("OP V: X " + optimalVelocity.getX() + ", Y " + optimalVelocity.getY());
            setVelocity(optimalVelocity);
        }
        robot.setVelocity(motionModel.getVelocityLeft(), motionModel.getVelocityRight());
        publishState(collide, createCollisionFreeState(optimalVelocity));
    }

    private void stop() {
        controlScheduler.shutdownNow();
        sensorMonitor.shutdownNow();
        publishState(false, State.createFinished(robotId));
        manager.onFinish(robotId, sensorReadCounter);
        robot.setVelocity(0.0,0.0);
    }

    private void setToDestinationVelocity(Velocity velocity, double destinationDistance) {
        double angleToTarget = getToVelocityAngle(velocity);
        if (Double.isNaN(angleToTarget)) {
            motionModel.setVelocity(0.0, 0.0);
        } else {
            motionModel.setLinearAndAngularVelocities(findLinearVelocity(angleToTarget, destinationDistance), calculateAngularVelocity(angleToTarget));
        }
    }

    private double findLinearVelocity(double angleToTarget, double destinationDistance) {
//        if (destination.isFinal()) {
//            return Math.cos(angleToTarget / 2.0) * Math.min(1.0, destinationDistance) * motionModel.getMaxLinearVelocity() / 2.0;
//        } else {
            return Math.cos(angleToTarget / 2.0) * motionModel.getMaxLinearVelocity() / 2.0;
//        }
    }

    private void setVelocity(Velocity velocity) {
        double angleToTarget = getToVelocityAngle(velocity);
        if (Double.isNaN(angleToTarget)) {
            motionModel.setVelocity(0.0, 0.0);
            return;
        }
        double speed = velocity.getSpeed();
        if (speed == 0.0) {
            motionModel.setLinearAndAngularVelocities(0.0, 0.0);
        } else {
            motionModel.setLinearAndAngularVelocities(velocity.getSpeed(), calculateAngularVelocity(angleToTarget));
        }
    }

    private double getToVelocityAngle(Velocity velocity) {
        if (velocity.getX() == 0.0 && velocity.getY() == 0.0) {
            return Double.NaN;
        }
        Vector2D robotUnitVector = motionModel.getUnitVector();
        return robotUnitVector.angleTo(velocity.toVector2D());
    }

    private Velocity findOptimalToDestinationVelocity() {
        double deltaX = destination.getX() - motionModel.getLocation().getX();
        double deltaY = destination.getY() - motionModel.getLocation().getY();
        double distance = Math.sqrt((deltaX * deltaX) + (deltaY * deltaY));
        if (distance == 0.0) {
            return new Velocity(0.0, 0.0);
        }
        double factor = EnvironmentalConfiguration.PREF_ROBOT_SPEED / distance;
        return new Velocity(deltaX * factor, deltaY * factor);
    }

    private State createCollisionFreeState(Velocity optimalVelocity) {
        return new State(robotId, motionModel.getLocation(), optimalVelocity, destination);
    }

    private void publishState(boolean collide, State state) {
        isSensorWorking = true;
        statePublisher.publishRobotState(state);
        manager.onNewState(robotId, collide, state);
    }

    private boolean findDestination(double destinationDistance) {
        if (!wallCollisionDetector.isDestinationVisible(motionModel.getLocation(), destination)) {
            path.add(0, lastReachDestination);
            destination = path.get(0);
            return true;
        }
        if (destinationDistance <= destination.getMargin()) {
            lastReachDestination = path.remove(0);
            if (path.size() > 0) {
                destination = path.get(0);
            } else {
                return false;
            }
        }
        return true;
    }

    private void monitorSensor() {
        if (isSensorWorking) {
            isSensorWorking = false;
        } else {
            reduceSpeedDueToSensorReadingTimeout();
        }
    }

    private void reduceSpeedDueToSensorReadingTimeout() {
        logger.debug("-> reduceSpeedDueToSensorReadingTimeout\n");
        robot.setVelocity(motionModel.getVelocityLeft() / 2, motionModel.getVelocityRight() / 2);
    }

    private double calculateAngularVelocity(double angleToTarget){
        return EnvironmentalConfiguration.ANGULAR_VELOCITY_FACTOR * angleToTarget;
    }
}
