#!/usr/bin/env python3
import os
import sys
import time
import json
import socket
import logging
import requests
from typing import Optional

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def get_ipv6_address(interface: str) -> Optional[str]:
    """
    Get the temporary IPv6 address from the specified interface.
    Temporary addresses are used for privacy and change periodically.
    """
    try:
        import netifaces
        
        if not interface:
            logger.error("NETWORK_INTERFACE is not specified")
            return None
        
        if interface == 'lo':
            logger.error("Cannot monitor loopback interface")
            return None
        
        try:
            addrs = netifaces.ifaddresses(interface)
            if netifaces.AF_INET6 not in addrs:
                logger.warning(f"No IPv6 address found on interface {interface}")
                return None
            
            temporary_addresses = []
            permanent_addresses = []
            
            for addr_info in addrs[netifaces.AF_INET6]:
                addr = addr_info['addr']
                # Remove zone ID if present
                if '%' in addr:
                    addr = addr.split('%')[0]
                
                # Skip link-local addresses (fe80::)
                if addr.startswith('fe80:'):
                    continue
                
                # Skip loopback
                if addr == '::1':
                    continue
                
                # Try to determine if this is a temporary address
                # Temporary addresses typically have more random patterns in the interface ID
                # while permanent addresses often have patterns like ff:fe or are based on MAC
                addr_parts = addr.lower().split(':')
                
                # Check for EUI-64 format (common in permanent addresses)
                # EUI-64 addresses have 'ff:fe' or 'fffe' in the middle of interface identifier
                is_eui64 = False
                if len(addr_parts) >= 4:
                    last_four = addr_parts[-4:]
                    addr_str = ''.join(last_four)
                    if 'fffe' in addr_str:
                        is_eui64 = True
                
                if is_eui64:
                    permanent_addresses.append(addr)
                    logger.debug(f"Found permanent IPv6 address {addr} on interface {interface}")
                else:
                    temporary_addresses.append(addr)
                    logger.debug(f"Found temporary IPv6 address {addr} on interface {interface}")
            
            # Prefer temporary addresses for privacy
            if temporary_addresses:
                selected_addr = temporary_addresses[0]
                logger.debug(f"Selected temporary IPv6 address: {selected_addr}")
                return selected_addr
            elif permanent_addresses:
                logger.warning(f"No temporary IPv6 address found on interface {interface}, using permanent address")
                return permanent_addresses[0]
            else:
                logger.warning(f"No suitable IPv6 address found on interface {interface}")
                return None
            
        except (ValueError, KeyError) as e:
            logger.error(f"Error processing interface {interface}: {e}")
            return None
        
    except ImportError:
        logger.error("netifaces module not installed. Please install it: pip install netifaces")
        sys.exit(1)
    except Exception as e:
        logger.error(f"Error getting IPv6 address: {e}")
        return None


def report_ip(server_url: str, api_token: str, ip_address: str) -> bool:
    """
    Report the IP address to the server.
    """
    try:
        headers = {
            'X-API-Token': api_token,
            'Content-Type': 'application/json'
        }
        
        data = {
            'ipAddress': ip_address
        }
        
        response = requests.post(
            f"{server_url}/api/v1/public-ip",
            headers=headers,
            json=data,
            timeout=10
        )
        
        if response.status_code == 200:
            logger.info(f"Successfully reported IP address: {ip_address}")
            return True
        else:
            logger.error(f"Failed to report IP address. Status code: {response.status_code}, Response: {response.text}")
            return False
    except requests.exceptions.RequestException as e:
        logger.error(f"Network error while reporting IP address: {e}")
        return False
    except Exception as e:
        logger.error(f"Unexpected error while reporting IP address: {e}")
        return False


def main():
    # Read configuration from environment variables
    server_url = os.getenv('SERVER_URL')
    api_token = os.getenv('API_TOKEN')
    check_interval = int(os.getenv('CHECK_INTERVAL', '5'))
    network_interface = os.getenv('NETWORK_INTERFACE')
    
    if not server_url:
        logger.error("SERVER_URL environment variable is not set")
        sys.exit(1)
    
    if not api_token:
        logger.error("API_TOKEN environment variable is not set")
        sys.exit(1)
    
    if not network_interface:
        logger.error("NETWORK_INTERFACE environment variable is not set")
        sys.exit(1)
    
    logger.info(f"Starting ks-gateway-autodns-agent")
    logger.info(f"Server URL: {server_url}")
    logger.info(f"Check interval: {check_interval} seconds")
    logger.info(f"Monitoring network interface: {network_interface}")
    
    last_reported_ip = None
    
    while True:
        try:
            current_ip = get_ipv6_address(network_interface)
            
            if current_ip:
                if current_ip != last_reported_ip:
                    logger.info(f"IPv6 address changed: {last_reported_ip} -> {current_ip}")
                    # Try to report the new IP, but update local state regardless of success
                    try:
                        report_ip(server_url, api_token, current_ip)
                    except Exception as e:
                        logger.error(f"Exception while reporting IP address: {e}")
                    # Always update last_reported_ip to avoid repeated attempts for the same address
                    last_reported_ip = current_ip
                else:
                    logger.debug(f"IPv6 address unchanged: {current_ip}")
            else:
                logger.warning("No IPv6 address detected")
            
        except Exception as e:
            logger.error(f"Error in main loop: {e}")
        
        time.sleep(check_interval)


if __name__ == '__main__':
    main()
