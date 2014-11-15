package org.matsim.core.mobsim.qsim.agents;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.VspExperimentalConfigGroup.ActivityDurationInterpretation;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.mobsim.framework.HasPerson;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.framework.VehicleUsingAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.population.PlanImpl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.misc.Time;
import org.matsim.vehicles.Vehicle;

public class BasicPlanAgentImpl implements MobsimAgent, PlanAgent, Identifiable<Person>, HasPerson, VehicleUsingAgent {

	private static final Logger log = Logger.getLogger(BasicPlanAgentImpl.class);
	private static int finalActHasDpTimeWrnCnt = 0;
	private static int noRouteWrnCnt = 0;
	
	private int currentPlanElementIndex = 0;
	private Plan plan;
	private boolean firstTimeToGetModifiablePlan = true;
	private final Scenario scenario;
	private final EventsManager events;
	private final MobsimTimer simTimer;
	private MobsimVehicle vehicle ;
	private double activityEndTime = Time.UNDEFINED_TIME;
	private MobsimAgent.State state = MobsimAgent.State.ABORT;
	private Id<Link> currentLinkId = null;

//	private transient Id<Link> cachedDestinationLinkId;
	// why is this transient?  "transient" means it is not included in automatic serialization/deserialization.  But where are we using this for this 
	// class?  kai, nov'14

	public BasicPlanAgentImpl(Plan plan2, Scenario scenario, EventsManager events, MobsimTimer simTimer) {
		this.plan = plan2 ;
		this.scenario = scenario ;
		this.events = events ;
		this.simTimer = simTimer ;
	}
	
	@Override
	public final PlanElement getCurrentPlanElement() {
		return this.plan.getPlanElements().get(this.currentPlanElementIndex);
	}

	@Override
	public final PlanElement getNextPlanElement() {
		if ( this.currentPlanElementIndex < this.plan.getPlanElements().size() ) {
			return this.plan.getPlanElements().get( this.currentPlanElementIndex+1 ) ;
		} else {
			return null ;
		}
	}


	/* default */ final int getCurrentPlanElementIndex() {
		return currentPlanElementIndex;
	}

	@Override
	public final Plan getCurrentPlan() {
		return plan;
	}

	/**
	 * Returns a modifiable Plan for use by WithinDayAgentUtils in this package.
	 * This agent retains the copied plan and forgets the original one.  However, the original plan remains in the population file
	 * (and will be scored).  This is deliberate behavior!
	 */
	final Plan getModifiablePlan() {
		if (firstTimeToGetModifiablePlan) {
			firstTimeToGetModifiablePlan = false ;
			PlanImpl newPlan = new PlanImpl(this.getCurrentPlan().getPerson());
			newPlan.copyFrom(this.getCurrentPlan());
			this.plan = newPlan;
		}
		return this.getCurrentPlan();
	}

	@Override
	public final Id<Person> getId() {
		return this.plan.getPerson().getId() ;
	}

	@Override
	public final Person getPerson() {
		return this.plan.getPerson() ;
	}

	final Scenario getScenario() {
		return scenario;
	}

	final EventsManager getEvents() {
		return events;
	}

	final MobsimTimer getSimTimer() {
		return simTimer;
	}

	@Override
	public MobsimVehicle getVehicle() {
		return vehicle;
	}

	@Override
	public final void setVehicle(MobsimVehicle vehicle) {
		this.vehicle = vehicle;
	}

	@Override
	public final Id<Vehicle> getPlannedVehicleId() {
		PlanElement currentPlanElement = this.getCurrentPlanElement();
		NetworkRoute route = (NetworkRoute) ((Leg) currentPlanElement).getRoute(); // if casts fail: illegal state.
		if (route.getVehicleId() != null) {
			return route.getVehicleId();
		} else {
	        if (!getScenario().getConfig().qsim().getUsePersonIdForMissingVehicleId()) {
	            throw new IllegalStateException("NetworkRoute without a specified vehicle id.");
	        }
			return Id.create(this.getId(), Vehicle.class); // we still assume the vehicleId is the agentId if no vehicleId is given.
		}
	}

	@Override
	public final Id<Link> getCurrentLinkId() {
		return this.currentLinkId;
	}
	
	/* package */ final void setCurrentLinkId( Id<Link> linkId ) {
		this.currentLinkId = linkId ;
	}

	@Override
	public final void endLegAndComputeNextState(final double now) {
		this.getEvents().processEvent(new PersonArrivalEvent( now, this.getId(), this.getDestinationLinkId(), getCurrentLeg().getMode()));
		if( (!(this.getCurrentLinkId() == null && this.getDestinationLinkId() == null)) 
				&& !this.getCurrentLinkId().equals(this.getDestinationLinkId())) {
			log.error("The agent " + this.getPerson().getId() + " has destination link " + this.getDestinationLinkId()
					+ ", but arrived on link " + this.getCurrentLinkId() + ". Removing the agent from the simulation.");
			this.setState(MobsimAgent.State.ABORT) ;
		} else {
			// note that when we are here we don't know if next is another leg, or an activity  Therefore, we go to a general method:
			advancePlan(now) ;
		}
	}

	@Override
	public final void setStateToAbort(final double now) {
		this.setState(MobsimAgent.State.ABORT) ;
	}

	@Override
	public final void notifyArrivalOnLinkByNonNetworkMode(final Id<Link> linkId) {
		this.setCurrentLinkId( linkId ) ;
	}

	private void advancePlan(double now) {
		//		this.planAgentDelegate.setCurrentPlanElementIndex(this.planAgentDelegate.getCurrentPlanElementIndex() + 1);
		this.currentPlanElementIndex++ ;
	
		// check if plan has run dry:
		if ( this.getCurrentPlanElementIndex() >= this.getCurrentPlan().getPlanElements().size() ) {
			log.error("plan of agent with id = " + this.getId() + " has run empty.  Setting agent state to ABORT\n" +
					"          (but continuing the mobsim).  This used to be an exception ...") ;
			this.setState(MobsimAgent.State.ABORT) ;
			return;
		}
	
		PlanElement pe = this.getCurrentPlanElement() ;
		if (pe instanceof Activity) {
			Activity act = (Activity) pe;
			initializeActivity(act, now);
		} else if (pe instanceof Leg) {
			Leg leg = (Leg) pe;
			initializeLeg(leg);
		} else {
			throw new RuntimeException("Unknown PlanElement of type: " + pe.getClass().getName());
		}
	}

	private void initializeLeg(Leg leg) {
		this.setState(MobsimAgent.State.LEG) ;			
		if (leg.getRoute() == null) {
			log.error("The agent " + this.getPerson().getId() + " has no route in its leg.  Setting agent state to ABORT " +
					"(but continuing the mobsim).");
			if ( noRouteWrnCnt < 1 ) {
				log.info( "(Route is needed inside Leg even if you want teleportation since Route carries the start/endLinkId info.)") ;
				noRouteWrnCnt++ ;
			}
			this.setState(MobsimAgent.State.ABORT) ;
		} 
	}

	private void initializeActivity(Activity act, double now) {
		this.setState(MobsimAgent.State.ACTIVITY) ;
		this.getEvents().processEvent( new ActivityStartEvent(now, this.getId(), this.getCurrentLinkId(), act.getFacilityId(), act.getType()));
		calculateAndSetDepartureTime(act);
	}

	/**
	 * If this method is called to update a changed ActivityEndTime please
	 * ensure, that the ActivityEndsList in the {@link QSim} is also updated.
	 */
	final void calculateAndSetDepartureTime(Activity act) {
		ActivityDurationInterpretation activityDurationInterpretation =
				(this.getScenario().getConfig().plans().getActivityDurationInterpretation());
		double now = this.getSimTimer().getTimeOfDay() ;
		double departure = ActivityDurationUtils.calculateDepartureTime(act, now, activityDurationInterpretation);
	
		if ( this.getCurrentPlanElementIndex() == this.getCurrentPlan().getPlanElements().size()-1 ) {
			if ( finalActHasDpTimeWrnCnt < 1 && departure!=Double.POSITIVE_INFINITY ) {
				log.error( "last activity of person driver agent id " + this.getId() + " has end time < infty; setting it to infty") ;
				log.error( Gbl.ONLYONCE ) ;
				finalActHasDpTimeWrnCnt++ ;
			}
			departure = Double.POSITIVE_INFINITY ;
		}
	
		this.activityEndTime = departure ;
	}

	@Override
	public final Id<Link> getDestinationLinkId() {
		return this.getCurrentLeg().getRoute().getEndLinkId() ;
	}

	@Override
	public final double getActivityEndTime() {
		// I don't think there is any guarantee that this entry is correct after an activity end re-scheduling.  kai, oct'10
		// Seems ok.  kai, nov'14
		return this.activityEndTime;
	}

	@Override
	public final Double getExpectedTravelTime() {
		PlanElement currentPlanElement = this.getCurrentPlanElement();
		if (!(currentPlanElement instanceof Leg)) {
			return null;
		}
		return ((Leg) currentPlanElement).getTravelTime();
	}

	@Override
	public final void endActivityAndComputeNextState(final double now) {
		//		Activity act = (Activity) this.getPlanElements().get(this.getCurrentPlanElementIndex());
		Activity act = (Activity) this.getCurrentPlanElement() ;
		this.getEvents().processEvent(
				new ActivityEndEvent(now, this.getPerson().getId(), act.getLinkId(), act.getFacilityId(), act.getType()));
	
		// note that when we are here we don't know if next is another leg, or an activity  Therefore, we go to a general method:
		advancePlan(now);
	}

	@Override
	public final String getMode() {
		if( this.getCurrentPlanElementIndex() >= this.getCurrentPlan().getPlanElements().size() ) {
			// just having run out of plan elements it not an argument for not being able to answer the "mode?" question,
			// this we answer with "null".  This will likely result in an "abort". kai, nov'14
			return null ;
		}
		PlanElement currentPlanElement = this.getCurrentPlanElement();
		if (!(currentPlanElement instanceof Leg)) {
			return null;
		}
		return ((Leg) currentPlanElement).getMode() ;
	}

	@Override
	public MobsimAgent.State getState() {
		return state;
	}

	final void setState(MobsimAgent.State state) {
		this.state = state;
	}
	
	final Leg getCurrentLeg() {
		return (Leg) this.getCurrentPlanElement() ;
	}
	
}