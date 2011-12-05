package org.openstack.atlas.rax.adapter.zxtm;

import com.zxtm.service.client.ObjectDoesNotExist;
import com.zxtm.service.client.ObjectInUse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.adapter.LoadBalancerEndpointConfiguration;
import org.openstack.atlas.adapter.exception.AdapterException;
import org.openstack.atlas.adapter.exception.BadRequestException;
import org.openstack.atlas.adapter.exception.RollbackException;
import org.openstack.atlas.adapter.zxtm.ZxtmAdapterImpl;
import org.openstack.atlas.adapter.zxtm.helper.ZxtmNameHelper;
import org.openstack.atlas.adapter.zxtm.service.ZxtmServiceStubs;
import org.openstack.atlas.datamodel.CoreProtocolType;
import org.openstack.atlas.rax.domain.entity.RaxAccessList;
import org.openstack.atlas.rax.domain.entity.RaxAccessListType;
import org.openstack.atlas.service.domain.entity.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.rmi.RemoteException;
import java.util.*;

@Primary
@Service
public class RaxZxtmAdapterImpl extends ZxtmAdapterImpl implements RaxZxtmAdapter {

    private static Log LOG = LogFactory.getLog(RaxZxtmAdapterImpl.class.getName());

    @Override
    public void addVirtualIps(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Set<VirtualIp> ipv4Vips, Set<VirtualIpv6> ipv6Vips) throws AdapterException {
        try {
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setAccountId(accountId);
            loadBalancer.setId(lbId);

            for (VirtualIp ipv4Vip : ipv4Vips) {
                LoadBalancerJoinVip joinVip = new LoadBalancerJoinVip(null, loadBalancer, ipv4Vip);
                loadBalancer.getLoadBalancerJoinVipSet().add(joinVip);
            }

            for (VirtualIpv6 ipv6Vip : ipv6Vips) {
                LoadBalancerJoinVip6 joinVip6 = new LoadBalancerJoinVip6(null, loadBalancer, ipv6Vip);
                loadBalancer.getLoadBalancerJoinVip6Set().add(joinVip6);
            }

            addVirtualIps(config, loadBalancer);
        } catch (RemoteException e) {
            throw new AdapterException(e);
        }
    }

    @Override
    public void deleteVirtualIps(LoadBalancerEndpointConfiguration config, LoadBalancer lb, List<Integer> vipIdsToDelete) throws AdapterException {
        try {
            ZxtmServiceStubs serviceStubs = getServiceStubs(config);
            final String virtualServerName = ZxtmNameHelper.generateNameWithAccountIdAndLoadBalancerId(lb.getId(), lb.getAccountId());
            String[][] currentTrafficIpGroups;
            List<String> updatedTrafficIpGroupList = new ArrayList<String>();
            final String rollBackMessage = "Delete virtual ip request canceled.";

            try {
                currentTrafficIpGroups = serviceStubs.getVirtualServerBinding().getListenTrafficIPGroups(new String[]{virtualServerName});
            } catch (Exception e) {
                if (e instanceof ObjectDoesNotExist) {
                    LOG.error("Cannot delete virtual ip from virtual server as the virtual server does not exist.", e);
                }
                LOG.error(rollBackMessage + "Rolling back changes...", e);
                throw new RollbackException(rollBackMessage, e);
            }

            // Convert current traffic groups to array
            List<String> trafficIpGroupNames = new ArrayList<String>();
            for (String[] currentTrafficGroup : currentTrafficIpGroups) {
                trafficIpGroupNames.addAll(Arrays.asList(currentTrafficGroup));
            }

            // Get traffic ip group to delete
            List<String> trafficIpGroupNamesToDelete = new ArrayList<String>();
            for (Integer vipIdToDelete : vipIdsToDelete) {
                trafficIpGroupNamesToDelete.add(ZxtmNameHelper.generateTrafficIpGroupName(lb, vipIdToDelete));
            }

            // Exclude the traffic ip group to delete
            for (String trafficIpGroupName : trafficIpGroupNames) {
                if (!trafficIpGroupNamesToDelete.contains(trafficIpGroupName)) {
                    updatedTrafficIpGroupList.add(trafficIpGroupName);
                    serviceStubs.getTrafficIpGroupBinding().setEnabled(new String[]{trafficIpGroupName}, new boolean[]{true});
                }
            }

            try {
                // Update the virtual server to listen on the updated traffic ip groups
                serviceStubs.getVirtualServerBinding().setListenTrafficIPGroups(new String[]{virtualServerName}, new String[][]{Arrays.copyOf(updatedTrafficIpGroupList.toArray(), updatedTrafficIpGroupList.size(), String[].class)});
            } catch (Exception e) {
                if (e instanceof ObjectDoesNotExist) {
                    LOG.error("Cannot set traffic ip groups to virtual server as it does not exist.", e);
                }
                throw new RollbackException(rollBackMessage, e);
            }

            if (!trafficIpGroupNamesToDelete.isEmpty()) {
                try {
                    deleteTrafficIpGroups(serviceStubs, trafficIpGroupNamesToDelete);
                } catch (RemoteException re) {
                    LOG.error(rollBackMessage + "Rolling back changes...", re);
                    serviceStubs.getVirtualServerBinding().setListenTrafficIPGroups(new String[]{virtualServerName}, new String[][]{Arrays.copyOf(trafficIpGroupNamesToDelete.toArray(), trafficIpGroupNamesToDelete.size(), String[].class)});
                    serviceStubs.getTrafficIpGroupBinding().setEnabled(trafficIpGroupNames.toArray(new String[trafficIpGroupNames.size()]), generateBooleanArray(trafficIpGroupNames.size(), true));
                    throw new RollbackException(rollBackMessage, re);
                }
            }
        } catch (RemoteException e) {
            throw new AdapterException(e);
        }
    }

    @Override
    public void updateAccessList(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, Collection<RaxAccessList> accessListItems) throws AdapterException {
        try {
            ZxtmServiceStubs serviceStubs = getServiceStubs(config);
            final String protectionClassName = ZxtmNameHelper.generateNameWithAccountIdAndLoadBalancerId(lbId, accountId);

            LOG.debug(String.format("Updating access list for protection class '%s'...", protectionClassName));

            if (addProtectionClass(config, protectionClassName)) {
                zeroOutConnectionThrottleConfig(config, lbId, accountId);
            }
            LOG.info("Removing the old access list...");
            //remove the current access list...
            deleteAccessList(config, lbId, accountId);

            LOG.debug("adding the new access list...");
            //add the new access list...
            serviceStubs.getProtectionBinding().setAllowedAddresses(new String[]{protectionClassName}, buildAccessListItems(accessListItems, RaxAccessListType.ALLOW));
            serviceStubs.getProtectionBinding().setBannedAddresses(new String[]{protectionClassName}, buildAccessListItems(accessListItems, RaxAccessListType.DENY));

            LOG.info(String.format("Successfully updated access list for protection class '%s'...", protectionClassName));
        } catch (RemoteException e) {
            throw new AdapterException(e);
        }

    }

    @Override
    public void deleteAccessList(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId) throws AdapterException {
        String poolName = "";
        try {
            ZxtmServiceStubs serviceStubs = getServiceStubs(config);
            poolName = ZxtmNameHelper.generateNameWithAccountIdAndLoadBalancerId(lbId, accountId);

            // TODO: Do we really need to remove addresses first or can we just call deleteProtection()?
            String[][] allowList = serviceStubs.getProtectionBinding().getAllowedAddresses(new String[]{poolName});
            String[][] bannedList = serviceStubs.getProtectionBinding().getBannedAddresses(new String[]{poolName});
            serviceStubs.getProtectionBinding().removeAllowedAddresses(new String[]{poolName}, allowList);
            serviceStubs.getProtectionBinding().removeBannedAddresses(new String[]{poolName}, bannedList);
            serviceStubs.getProtectionBinding().deleteProtection(new String[]{poolName});
        } catch (ObjectDoesNotExist odne) {
            LOG.warn(String.format("Protection class '%s' already deleted.", poolName));
        } catch (ObjectInUse oiu) {
            LOG.warn(String.format("Protection class '%s' is currently in use. Cannot delete.", poolName));
        } catch (RemoteException e) {
            throw new AdapterException(e);
        }
    }

    @Override
    public void updateConnectionLogging(LoadBalancerEndpointConfiguration config, Integer accountId, Integer lbId, boolean isConnectionLogging, String protocol) throws AdapterException {
        try {
            ZxtmServiceStubs serviceStubs = getServiceStubs(config);
            final String virtualServerName = ZxtmNameHelper.generateNameWithAccountIdAndLoadBalancerId(lbId, accountId);
            final String rollBackMessage = "Update connection logging request canceled.";
            final String nonHttpLogFormat = "%v %t %h %A:%p %n %B %b %T";
            final String httpLogFormat = "%v %{Host}i %h %l %u %t \"%r\" %s %b \"%{Referer}i\" \"%{User-Agent}i\"";

            if (isConnectionLogging) {
                LOG.debug(String.format("ENABLING logging for virtual server '%s'...", virtualServerName));
            } else {
                LOG.debug(String.format("DISABLING logging for virtual server '%s'...", virtualServerName));
            }

            try {
                if (protocol != CoreProtocolType.HTTP) {
                    serviceStubs.getVirtualServerBinding().setLogFormat(new String[]{virtualServerName}, new String[]{nonHttpLogFormat});
                } else if (protocol == CoreProtocolType.HTTP) {
                    serviceStubs.getVirtualServerBinding().setLogFormat(new String[]{virtualServerName}, new String[]{httpLogFormat});
                }
                serviceStubs.getVirtualServerBinding().setLogFilename(new String[]{virtualServerName}, new String[]{config.getLogFileLocation()});
                serviceStubs.getVirtualServerBinding().setLogEnabled(new String[]{virtualServerName}, new boolean[]{isConnectionLogging});
            } catch (Exception e) {
                if (e instanceof ObjectDoesNotExist) {
                    LOG.error(String.format("Virtual server '%s' does not exist. Cannot update connection logging.", virtualServerName));
                }
                throw new RollbackException(rollBackMessage, e);
            }

            LOG.info(String.format("Successfully updated connection logging for virtual server '%s'...", virtualServerName));
        } catch (RemoteException e) {
            throw new AdapterException(e);
        }
    }

    private String[][] buildAccessListItems(Collection<RaxAccessList> accessListItems, RaxAccessListType type) throws BadRequestException {
        String[][] list;

        if (type == RaxAccessListType.ALLOW) {
            List<RaxAccessList> accessList = getFilteredList(accessListItems, RaxAccessListType.ALLOW);
            list = new String[1][accessList.size()];
            for (int i = 0; i < accessList.size(); i++) {
                list[0][i] = accessList.get(i).getIpAddress();
            }
        } else if (type == RaxAccessListType.DENY) {
            List<RaxAccessList> accessList = getFilteredList(accessListItems, RaxAccessListType.DENY);
            list = new String[1][accessList.size()];
            for (int i = 0; i < accessList.size(); i++) {
                list[0][i] = accessList.get(i).getIpAddress();
            }
        } else {
            throw new BadRequestException(String.format("Unsupported rule type '%s' found when building item list", type));
        }

        return list;
    }

    private List<RaxAccessList> getFilteredList(Collection<RaxAccessList> accessListItems, RaxAccessListType type) {
        List<RaxAccessList> filteredItems = new ArrayList<RaxAccessList>();

        for (RaxAccessList item : accessListItems) {
            if (item.getType() == type) {
                filteredItems.add(item);
            }
        }

        return filteredItems;
    }

    private boolean[] generateBooleanArray(int size, boolean value) {
        boolean[] array = new boolean[size];

        for (int i = 0; i < array.length; i++) {
            array[i] = value;
        }

        return array;
    }
}
