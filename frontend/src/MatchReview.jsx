import { useEffect, useRef, useState } from 'react';

// Matches SpotifyPlaylistService.MAX_CANDIDATES_PER_SONG; the server already caps the list.
const MAX_RECORDINGS_PER_SONG = 3;

const reasons = {
  TITLE_VARIANT: 'Spotify lists this recording with additional title details.',
  ARTIST_MISMATCH: 'The title matches, but the artist credit differs.',
  MULTIPLE_RECORDINGS: 'Spotify returned multiple recordings.',
  NO_EXACT_TITLE: 'No exact title match in the Spotify results.',
  MISSING_TITLE: 'The setlist did not give this song a title.',
};

function spotifyTrackLink(candidate) {
  if (candidate.spotifyUrl?.startsWith('https://open.spotify.com/track/')) {
    return candidate.spotifyUrl;
  }
  const track = /^spotify:track:([a-zA-Z0-9]+)$/.exec(candidate.uri);
  if (track) {
    return `https://open.spotify.com/track/${track[1]}`;
  }
  return null;
}

function CandidateOption({ candidate, songId, checked, onChoose }) {
  const link = spotifyTrackLink(candidate);
  const image = candidate.album?.images?.find(({ url }) => url.startsWith('https://'));

  return (
    <div className="candidate-option">
      <label>
        <input type="radio" name={`match-${songId}`} checked={checked} onChange={onChoose} />
        <span className="candidate-details">
          {image && <img src={image.url} alt="" width="48" height="48" loading="lazy" />}
          <span>
            <strong>{candidate.name}</strong>
            {candidate.artists.length > 0 && <span>{candidate.artists.join(', ')}</span>}
            {candidate.album?.name && <span>{candidate.album.name}</span>}
          </span>
        </span>
      </label>
      {link && <a href={link} target="_blank" rel="noreferrer">Open in Spotify</a>}
    </div>
  );
}

function SongList({ items, listClass, label, renderSong }) {
  return (
    <div className="track-scroll" role="region" aria-label={label} tabIndex="0">
      <ol className={listClass}>{items.map(renderSong)}</ol>
    </div>
  );
}

function retryDelay(header) {
  if (!header) {
    return 0;
  }
  if (/^\d+$/.test(header)) {
    return Number(header) * 1000;
  }
  return Math.max(0, Date.parse(header) - Date.now()) || 0;
}

export default function MatchReview({ setlistId, concertDate, venueName, tracks, onLogin }) {
  const [songs, setSongs] = useState(null);
  const [choices, setChoices] = useState({});
  const [status, setStatus] = useState('idle');
  const [message, setMessage] = useState('');
  const [retryAt, setRetryAt] = useState(0);
  const [reviewId, setReviewId] = useState('');
  const [playlistName, setPlaylistName] = useState('');
  const [creation, setCreation] = useState({ status: 'idle', message: '', link: null });
  const reviewTitle = useRef(null);
  const connectLink = useRef(null);
  const previewRequest = useRef(null);

  useEffect(() => () => previewRequest.current?.abort(), []);

  useEffect(() => {
    if (status === 'ready') {
      reviewTitle.current?.focus();
    } else if (status === 'sign-in') {
      connectLink.current?.focus();
    }
  }, [status]);

  useEffect(() => {
    if (!retryAt) {
      return;
    }
    const timeout = window.setTimeout(() => setRetryAt(0), Math.min(2147483647, Math.max(0, retryAt - Date.now())));
    return () => window.clearTimeout(timeout);
  }, [retryAt]);

  async function loadPreview() {
    previewRequest.current?.abort();
    const request = new AbortController();
    previewRequest.current = request;
    setStatus('loading');
    setMessage(`Matching ${tracks.length} songs on Spotify…`);
    setSongs(null);
    setCreation({ status: 'idle', message: '', link: null });
    try {
      const response = await fetch('/api/v1/playlist-previews', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ setlistId }),
        signal: request.signal,
      });
      if (!response.ok) {
        const error = await response.json().catch(() => null);
        if (request.signal.aborted) {
          return;
        }
        if (response.status === 401) {
          setStatus('sign-in');
          setMessage('Connect Spotify to review these songs.');
          return;
        }
        const delay = retryDelay(response.headers.get('Retry-After'));
        if (delay > 0) {
          setRetryAt(Date.now() + delay);
        }
        setStatus('error');
        setMessage(error?.message || 'Could not load Spotify matches. Please try again.');
        return;
      }
      const preview = await response.json();
      if (request.signal.aborted) {
        return;
      }
      if (preview.setlistId !== setlistId || !preview.reviewId || !Array.isArray(preview.songs)) {
        throw new Error('Unexpected preview response');
      }

      const initialChoices = {};
      for (const song of preview.songs) {
        const firstCandidate = song.candidates[0];
        if ((song.status === 'MATCHED' || song.status === 'NEEDS_REVIEW') && firstCandidate) {
          initialChoices[song.songId] = firstCandidate.uri;
        }
      }
      setChoices(initialChoices);
      setReviewId(preview.reviewId);
      const nameParts = [preview.artistName, concertDate, venueName]
        .map((part) => part?.trim())
        .filter((part) => part && part !== 'Unknown Venue');
      const suggestedName = nameParts.join(' — ').slice(0, 100).trimEnd();
      setPlaylistName((current) => current || suggestedName);
      setSongs(preview.songs);
      setStatus('ready');
      setMessage(`Loaded ${preview.songs.length} songs for review.`);
    } catch {
      if (request.signal.aborted) {
        return;
      }
      setStatus('error');
      setMessage('Could not load Spotify matches. Please try again.');
    }
  }

  function choose(songId, uri) {
    setChoices((previous) => ({ ...previous, [songId]: uri }));
  }

  const selections = Object.entries(choices)
    .filter(([, trackUri]) => Boolean(trackUri))
    .map(([songId, trackUri]) => ({ songId: Number(songId), trackUri }));
  const canSubmit = creation.status === 'idle' || creation.status === 'invalid';

  async function makePlaylist(event) {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }
    const name = playlistName.trim();
    if (!name || selections.length === 0) {
      let message = 'Select at least one song.';
      if (!name) {
        message = 'Enter a playlist name.';
      }
      setCreation({ status: 'invalid', message, link: null });
      return;
    }

    setCreation({ status: 'saving', message: 'Creating your playlist…', link: null });
    try {
      const response = await fetch('/api/v1/playlists', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reviewId, playlistName: name, selections }),
      });
      const result = await response.json().catch(() => null);
      let link = null;
      if (result?.spotifyUrl?.startsWith('https://open.spotify.com/playlist/')) {
        link = result.spotifyUrl;
      }
      if (response.status === 201) {
        setCreation({ status: 'done', message: 'Your private Spotify playlist is ready.', link });
      } else if (response.status === 401) {
        setCreation({ status: 'sign-in', message: 'Connect Spotify again and reload the review.', link: null });
      } else if (response.status === 409) {
        setCreation({ status: 'expired', message: result?.message || 'Review the matches again.', link: null });
      } else if (response.status === 400 || response.status === 422) {
        setCreation({ status: 'invalid', message: result?.message || 'Check your choices and try again.', link: null });
      } else {
        let message = result?.message || 'Playlist creation could not be confirmed.';
        if (!link) {
          message += ' Check your Spotify library before starting a new review.';
        }
        setCreation({ status: 'failed', message, link });
      }
    } catch {
      setCreation({ status: 'failed', message: 'The request was interrupted. Check your Spotify library before starting a new review.', link: null });
    }
  }

  let actionLabel = 'Review Spotify matches';
  if (status === 'loading') {
    actionLabel = 'Loading matches…';
  } else if (status === 'error') {
    actionLabel = 'Try again';
  }

  return (
    <section className="match-review" aria-label="Spotify match review">
      {!songs && (
        <SongList items={tracks} listClass="preview-tracks" label="Setlist songs"
          renderSong={(song, index) => <li key={index}>{song || 'Unknown song'}</li>} />
      )}
      {status === 'sign-in' && (
        <a ref={connectLink} className="spotify-login" href="/api/v1/auth/login" onClick={onLogin}>Connect Spotify</a>
      )}
      {status !== 'sign-in' && status !== 'ready' && (
        <button className="search-button" type="button" onClick={loadPreview} disabled={status === 'loading' || retryAt > 0}>
          {actionLabel}
        </button>
      )}
      <p className="match-status" role="status">{message}</p>
      {songs && (
        <>
          <h4 ref={reviewTitle} tabIndex="-1">Review Spotify matches</h4>
          <div className="match-source">
            <span>Matches from</span>
            <img src="https://developer-assets.spotifycdn.com/images/guidelines/design/full-logo-framed.svg" alt="Spotify" />
          </div>
          <div className="review-layout">
            <SongList items={songs} listClass="match-list" label="Spotify match choices"
              renderSong={(song) => {
                const title = song.title || 'Unknown song';
                return (
                  <li key={song.songId}>
                    {song.status === 'UNMATCHED' && (
                      <div className="unmatched-song">
                        <strong>{title}</strong>
                        <p>{reasons[song.reason] || 'No Spotify match.'} This song will be skipped.</p>
                      </div>
                    )}
                    {song.status !== 'UNMATCHED' && (
                      <fieldset className="song-choices" disabled={!canSubmit}>
                        <legend>{title}</legend>
                        <p>{reasons[song.reason] || 'Matched automatically.'}</p>
                        <div className="candidate-options">
                          {song.candidates.slice(0, MAX_RECORDINGS_PER_SONG).map((candidate) => (
                            <CandidateOption key={candidate.uri} candidate={candidate} songId={song.songId}
                              checked={choices[song.songId] === candidate.uri}
                              onChoose={() => choose(song.songId, candidate.uri)} />
                          ))}
                        </div>
                        <label className="skip-choice">
                          <input type="radio" name={`match-${song.songId}`} checked={choices[song.songId] === ''}
                            onChange={() => choose(song.songId, '')} />
                          Skip this song
                        </label>
                      </fieldset>
                    )}
                  </li>
                );
              }} />
            <form className="make-playlist" action="/api/v1/playlists" method="post"
              aria-labelledby="playlist-heading" onSubmit={makePlaylist}>
              <h4 id="playlist-heading">Make a private playlist</h4>
              <p className="field-hint">{selections.length} selected. The first suggestion is selected by default when a match is uncertain. You can change or skip any selection.</p>
              <label htmlFor="playlist-name">Playlist name</label>
              <input id="playlist-name" name="playlistName" value={playlistName}
                onChange={(event) => setPlaylistName(event.target.value)} required maxLength={100} disabled={!canSubmit} />
              {(canSubmit || creation.status === 'saving') && (
                <button className="search-button" type="submit" disabled={creation.status === 'saving'}>Make playlist</button>
              )}
              <p className="match-status" role="status">{creation.message}</p>
              {creation.status === 'expired' && (
                <button className="search-button" type="button" onClick={loadPreview}>Review matches again</button>
              )}
              {creation.status === 'sign-in' && (
                <a className="spotify-login" href="/api/v1/auth/login" onClick={onLogin}>Connect Spotify</a>
              )}
              {creation.link && (
                <a className="playlist-result-link" href={creation.link} target="_blank" rel="noreferrer">Open playlist in Spotify</a>
              )}
            </form>
          </div>
        </>
      )}
    </section>
  );
}
