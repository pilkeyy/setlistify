import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App, { readSpotifyReturn } from './App.jsx';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App spotifyReturn={readSpotifyReturn()} />
  </StrictMode>,
);
