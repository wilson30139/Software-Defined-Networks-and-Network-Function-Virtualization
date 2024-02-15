/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nycu.sdnfv.vrouter;

import java.util.List;

import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.Config;
import org.onlab.packet.IpAddress;

public class VirtualRouterConfig extends Config<ApplicationId> {
  
  public static final String QUAGGA_CONNECTED_POINT = "quagga";

  public static final String QUAGGA_MAC_ADDRESS = "quagga-mac";
  
  public static final String VIRTUAL_ROUTER_IP_ADDRESS = "virtual-ip";
  
  public static final String VIRTUAL_ROUTER_MAC_ADDRESS = "virtual-mac";
  
  public static final String EXTERNAL_ROUTER_IP_ADDRESS_LIST = "peers";

  @Override
  public boolean isValid() {
    return hasOnlyFields(QUAGGA_CONNECTED_POINT, QUAGGA_MAC_ADDRESS, VIRTUAL_ROUTER_IP_ADDRESS, VIRTUAL_ROUTER_MAC_ADDRESS, EXTERNAL_ROUTER_IP_ADDRESS_LIST);
  }

  public ConnectPoint GetQuaggaConnectedPoint() {
    String quaggaConnectedPointString = get(QUAGGA_CONNECTED_POINT, null);
    String[] quaggaConnectedPointStringArray = quaggaConnectedPointString.split("/");
    ConnectPoint quaggaConnectedPoint = new ConnectPoint(DeviceId.deviceId(quaggaConnectedPointStringArray[0]), PortNumber.portNumber(quaggaConnectedPointStringArray[1]));
    return quaggaConnectedPoint;
  }

  public MacAddress GetQuaggaMacAddress() {
    String quaggaMacAddressString = get(QUAGGA_MAC_ADDRESS, null);
    MacAddress quaggaMacAddress = MacAddress.valueOf(quaggaMacAddressString);
    return quaggaMacAddress;
  }

  public IpAddress GetVirtualRouterIpAddress() {
    String virtualRouterIpAddressString = get(VIRTUAL_ROUTER_IP_ADDRESS, null);
    IpAddress virtualRouterIpAddress = IpAddress.valueOf(virtualRouterIpAddressString);
    return virtualRouterIpAddress;
  }

  public MacAddress GetVirtualRouterMacAddress() {
    String virtualRouterMacAddressString = get(VIRTUAL_ROUTER_MAC_ADDRESS, null);
    MacAddress virtualRouterMacAddress = MacAddress.valueOf(virtualRouterMacAddressString);
    return virtualRouterMacAddress;
  }

  public List<IpAddress> GetExternalRouterIpAddressList() {
    return getList(EXTERNAL_ROUTER_IP_ADDRESS_LIST, IpAddress::valueOf, null);
  }
}
