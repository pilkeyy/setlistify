import { useEffect, useRef, useState } from 'react';
import MatchReview from './MatchReview.jsx';

const loginContextKey = 'setlistify.spotify-login';

export function readSpotifyReturn() {
  const url = new URL(window.location.href);
  const result = url.searchParams.get('spotify');
  if (result !== 'connected' && result !== 'failed') {
    return {};
  }
  url.searchParams.delete('spotify');
  window.history.replaceState(window.history.state, '', url);

  let context = {};
  try {
    const stored = window.sessionStorage.getItem(loginContextKey);
    window.sessionStorage.removeItem(loginContextKey);
    context = JSON.parse(stored) || {};
  } catch {
    // Login still works when the browser blocks session storage.
  }
  return { ...context, result };
}

function formatConcertDate(value, fallback = 'Date unavailable') {
  if (typeof value !== 'string') {
    return fallback;
  }

  const match = /^(\d{2})-(\d{2})-(\d{4})$/.exec(value);
  if (!match) {
    return value;
  }

  const day = Number(match[1]);
  const month = Number(match[2]);
  const year = Number(match[3]);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (leapYear) {
    daysInMonth[1] = 29;
  }
  if (year === 0 || month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1]) {
    return value;
  }
  return `${months[month - 1]} ${day}, ${year}`;
}

function App({ spotifyReturn = {} }) {
  const [artist, setArtist] = useState(spotifyReturn.artist || '');
  const [searchedArtist, setSearchedArtist] = useState(spotifyReturn.searchedArtist || '');
  const [selectedShow, setSelectedShow] = useState(spotifyReturn.selectedShow || '');
  const [shows, setShows] = useState(spotifyReturn.shows || []);
  const [status, setStatus] = useState(spotifyReturn.searchStatus || 'idle');
  const [errorMessage, setErrorMessage] = useState('');
  const [tracks, setTracks] = useState([]);
  const [trackStatus, setTrackStatus] = useState('idle');
  const [trackAttempt, setTrackAttempt] = useState(0);
  const reviewHeading = useRef(null);
  const selectedConcert = shows.find((show) => show.id === selectedShow);

  useEffect(() => {
    if (selectedShow && reviewHeading.current) {
      reviewHeading.current.focus();
    }
  }, [selectedShow]);

  useEffect(() => {
    if (!selectedShow) {
      return;
    }
    const request = new AbortController();

    async function loadTracks() {
      try {
        const response = await fetch(`/api/v1/setlists/${encodeURIComponent(selectedShow)}/tracks`, {
          signal: request.signal,
        });
        if (!response.ok) {
          throw new Error('Could not load songs');
        }
        const songs = await response.json();
        if (!Array.isArray(songs)) {
          throw new Error('Unexpected songs response');
        }
        if (!request.signal.aborted) {
          setTracks(songs);
          setTrackStatus('success');
        }
      } catch {
        if (!request.signal.aborted) {
          setTrackStatus('error');
        }
      }
    }

    setTrackStatus('loading');
    loadTracks();
    return () => request.abort();
  }, [selectedShow, trackAttempt]);

  async function handleSearch(event) {
    event.preventDefault();
    const cleanedArtist = artist.trim();

    if (cleanedArtist.length === 0) {
      return;
    }

    setSearchedArtist(cleanedArtist);
    setSelectedShow('');
    setTracks([]);
    setTrackStatus('idle');
    setShows([]);
    setErrorMessage('');
    setStatus('loading');

    try {
      const params = new URLSearchParams({ artist: cleanedArtist });
      const response = await fetch(`/api/v1/setlists/search?${params}`);
      if (!response.ok) {
        throw new Error('Could not load concerts. Please try again.');
      }
      const concerts = await response.json();
      if (!Array.isArray(concerts)) {
        throw new Error('The server returned an unexpected response. Please try again.');
      }
      setShows(concerts);
      setStatus('success');
    } catch (error) {
      setErrorMessage('Could not load concerts. Check your connection and try again.');
      setStatus('error');
    }
  }

  function handleShowSelect(showId) {
    setSelectedShow(showId);
    setTracks([]);
    setTrackStatus('loading');
  }

  function handleBackToShows() {
    setSelectedShow('');
    setTracks([]);
    setTrackStatus('idle');
  }

  function rememberConcertForLogin() {
    let searchStatus = 'idle';
    if (status === 'success') {
      searchStatus = 'success';
    }
    try {
      window.sessionStorage.setItem(loginContextKey, JSON.stringify({
        artist, searchedArtist, selectedShow, shows, searchStatus,
      }));
    } catch {
      // The browser can still navigate to Spotify without saving the concert.
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="wordmark" href="#home" aria-label="Setlistify home">
          <span className="wordmark-mark" aria-hidden="true">S</span>
          <span>setlistify</span>
        </a>
        <p className="topbar-note">Your next show, on repeat.</p>
        <a className="spotify-login" href="/api/v1/auth/login" onClick={rememberConcertForLogin}>
          <span className="spotify-dot" aria-hidden="true" />
          Connect Spotify
        </a>
      </header>

      <main id="home" className="main-content" data-concert-selected={Boolean(selectedShow)}>
        <section className="intro" aria-labelledby="page-title">
          <p className="eyebrow">A concert setlist, made yours</p>
          <h1 id="page-title">Pick a show.<br /><span>Keep the set.</span></h1>
          <p className="intro-copy">
            Find a recent concert and turn the songs they played into a Spotify
            playlist you can take with you.
          </p>

          <div className="playlist-preview" aria-label="Example playlist preview">
            <div className="preview-header"><span className="preview-icon" aria-hidden="true">♫</span><span>Playlist preview</span><span className="preview-label">Example</span></div>
            <ol className="preview-tracks">
              <li>Opening Night</li>
              <li>Electric Heart</li>
              <li>Back Tonight</li>
            </ol>
          </div>

          <ol className="progress-list" aria-label="Playlist creation steps">
            <li className="current-step" aria-current="step">
              <span className="step-mark">1</span>
              <span>Find an artist</span>
            </li>
            <li><span className="step-mark">2</span><span>Choose a show</span></li>
            <li><span className="step-mark">3</span><span>Make a playlist</span></li>
          </ol>
        </section>

        <section className="search-panel" aria-labelledby="search-title">
          <div className="panel-heading">
            <div>
              <p className="section-index">Start with the artist</p>
              <h2 id="search-title">Which show are you going to?</h2>
            </div>
            <span className="panel-spark" aria-hidden="true">✳</span>
          </div>
          {spotifyReturn.result === 'connected' && <p className="field-hint">Connected to Spotify.</p>}
          {spotifyReturn.result === 'failed' && <p role="alert">Spotify sign-in did not complete. Try connecting again.</p>}

          <search>
            <form className="artist-search" action="/api/v1/setlists/search" method="get" onSubmit={handleSearch}>
              <label htmlFor="artist-name">Artist name</label>
              <div className="search-controls">
                <input
                  id="artist-name"
                  name="artist"
                  type="search"
                  value={artist}
                  onChange={(event) => setArtist(event.target.value)}
                  placeholder="Try Bad Bunny"
                  autoComplete="off"
                  required
                />
                <button className="search-button" type="submit" disabled={status === 'loading'}>
                  Search shows <span aria-hidden="true">↗</span>
                </button>
              </div>
              <p className="field-hint">Search setlists by artist name.</p>
            </form>
          </search>

          <div role="status" aria-live="polite">
            {status === 'loading' && <p>Searching for concerts…</p>}
            {status === 'error' && <p>{errorMessage}</p>}
            {status === 'success' && shows.length === 0 && <p>No concerts found for {searchedArtist}.</p>}
            {status === 'success' && shows.length > 0 && <p>Found {shows.length} concerts.</p>}
          </div>

          {status === 'success' && shows.length > 0 && !selectedShow && (
            <section className="results" aria-labelledby="results-title">
              <div className="results-heading">
                <div>
                  <p className="section-index">Recent setlists for</p>
                  <h3 id="results-title">{searchedArtist}</h3>
                </div>

              </div>

              <ul className="show-list">
                {shows.map((show) => (
                  <li key={show.id}>
                    <button
                      className={`show-option${selectedShow === show.id ? ' selected' : ''}`}
                      type="button"
                      aria-pressed={selectedShow === show.id}
                      onClick={() => handleShowSelect(show.id)}
                    >
                      <span className="show-date">{formatConcertDate(show.eventDate)}</span>
                      <span className="show-details">
                        <strong>{show.venueName}</strong>
                        <span>{show.cityName}, {show.countryName}</span>
                      </span>
                      <span className="selection-mark" aria-hidden="true">
                        {selectedShow === show.id ? '✓' : '＋'}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>


            </section>
          )}
          {selectedShow && (
            <section className="results" aria-labelledby="tracks-title">
              <div className="concert-tracks-heading">
                <h3 id="tracks-title" ref={reviewHeading} tabIndex="-1">Songs from this show</h3>
                <button className="back-to-concerts" type="button" onClick={handleBackToShows}>Back to concerts</button>
              </div>
              <div role="status">
                {trackStatus === 'loading' && <p>Loading songs…</p>}
                {trackStatus === 'error' && <p>Could not load songs. Please try again.</p>}
                {trackStatus === 'success' && tracks.length === 0 && <p>No songs are listed for this show.</p>}
                {trackStatus === 'success' && tracks.length > 0 && <p>Found {tracks.length} songs.</p>}
              </div>
              {trackStatus === 'error' && <button type="button" onClick={() => setTrackAttempt((attempt) => attempt + 1)}>Try again</button>}
              {trackStatus === 'success' && tracks.length > 0 && (
                <MatchReview
                  key={selectedShow}
                  setlistId={selectedShow}
                  concertDate={formatConcertDate(selectedConcert?.eventDate, '')}
                  venueName={selectedConcert?.venueName}
                  tracks={tracks}
                  onLogin={rememberConcertForLogin}
                />
              )}
            </section>
          )}
        </section>
      </main>

      <footer className="site-footer">
        <span>Built for the songs that stay with you.</span>
        <span>Setlistify · early demo</span>
      </footer>
    </div>
  );
}

export default App;
