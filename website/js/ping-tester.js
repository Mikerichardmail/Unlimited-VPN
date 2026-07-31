/* PulseVPN Live Server Latency Simulation Widget */
document.addEventListener('DOMContentLoaded', () => {
  const serverListContainer = document.getElementById('serverPingList');
  if (!serverListContainer) return;

  const servers = [
    { name: 'India (Mumbai - High Speed)', flag: '🇮🇳', basePing: 12, range: 4 },
    { name: 'Singapore (Asia Pacific Hub)', flag: '🇸🇬', basePing: 24, range: 6 },
    { name: 'Germany (Frankfurt Central)', flag: '🇩🇪', basePing: 85, range: 10 },
    { name: 'United States (New York East)', flag: '🇺🇸', basePing: 140, range: 15 },
    { name: 'United Kingdom (London)', flag: '🇬🇧', basePing: 95, range: 8 },
    { name: 'Japan (Tokyo Gaming Node)', flag: '🇯🇵', basePing: 65, range: 7 }
  ];

  function renderServers() {
    serverListContainer.innerHTML = '';
    servers.forEach(server => {
      // Generate realistic jitter
      const currentPing = server.basePing + Math.floor(Math.random() * server.range);
      const pingPercent = Math.min(100, Math.max(10, 100 - (currentPing / 2)));
      
      let pingClass = 'accent-green';
      if (currentPing > 100) pingClass = 'accent-orange';

      const itemHtml = `
        <div class="server-item">
          <div class="server-info">
            <span class="flag-icon">${server.flag}</span>
            <div class="server-details">
              <h4>${server.name}</h4>
              <span>WireGuard® • 10 Gbps Port</span>
            </div>
          </div>
          <div class="ping-bar-container">
            <span class="ping-val" style="color: ${currentPing < 50 ? '#00ff87' : (currentPing < 120 ? '#00f0ff' : '#ff9900')}">${currentPing} ms</span>
            <div class="ping-meter">
              <div class="ping-fill" style="width: ${pingPercent}%; background: ${currentPing < 50 ? '#00ff87' : (currentPing < 120 ? '#00f0ff' : '#ff9900')};"></div>
            </div>
          </div>
        </div>
      `;
      serverListContainer.insertAdjacentHTML('beforeend', itemHtml);
    });
  }

  // Initial render
  renderServers();

  // Periodic real-time update jitter every 3 seconds to simulate dynamic ping measurements
  setInterval(renderServers, 3000);
});
