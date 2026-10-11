const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('milkyway', Object.freeze({
  config: () => ipcRenderer.invoke('milkyway', 'config'),
  login: input => ipcRenderer.invoke('milkyway', 'login', input),
  logout: () => ipcRenderer.invoke('milkyway', 'logout'),
  view: (day, queueDay) => ipcRenderer.invoke('milkyway', 'view', { day, queueDay }),
  submit: input => ipcRenderer.invoke('milkyway', 'submit', input)
}));
