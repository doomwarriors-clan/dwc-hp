(() => {
    const list = document.getElementById('score-list');
    const template = document.getElementById('score-template');
    const addScore = document.getElementById('add-score');
    if (!list || !template || !addScore) return;

    let nextKey = 0;
    let nextDemoKey = 0;
    addScore.addEventListener('click', () => {
        const key = 'n' + nextKey++;
        const wrapper = document.createElement('div');
        wrapper.innerHTML = template.innerHTML.replaceAll('__KEY__', key).trim();
        list.appendChild(wrapper.firstElementChild);
    });
    document.addEventListener('click', event => {
        const imageButton = event.target.closest('.add-image');
        if (imageButton) {
            const name = imageButton.dataset.target;
            const target = document.querySelector('[data-new-images="' + name + '"]');
            const input = document.createElement('input');
            input.type = 'text';
            input.name = name;
            input.placeholder = 'Screenshot filename';
            target.appendChild(input);
        }
        const demoButton = event.target.closest('.add-demo');
        if (demoButton) {
            // File, recorder and row key go in triples: the server matches them up by their order.
            // An empty file field sends nothing, so the key tells which row an uploaded file belongs to.
            const name = demoButton.dataset.target;
            const key = 'd' + nextDemoKey++;
            const row = document.createElement('div');
            row.className = 'admin-image-row';
            [['File', 'Demo filename', 'demo-files'], ['Player', 'Recorded by', 'demo-players']].forEach(([suffix, placeholder, list]) => {
                const input = document.createElement('input');
                input.type = 'text';
                input.name = name + suffix;
                input.placeholder = placeholder;
                input.setAttribute('list', list);
                row.appendChild(input);
            });
            const rowKey = document.createElement('input');
            rowKey.type = 'hidden';
            rowKey.name = name + 'Upload';
            rowKey.value = key;
            row.appendChild(rowKey);
            const upload = document.createElement('input');
            upload.type = 'file';
            upload.name = 'demoUpload_' + key;
            upload.setAttribute('aria-label', 'Upload demo');
            // The uploaded file replaces whatever name was typed in. Read-only rather than
            // disabled: a disabled field is not sent and would shift the triples.
            upload.addEventListener('change', () => {
                const file = row.querySelector('input[name="' + name + 'File"]');
                file.readOnly = upload.files.length > 0;
                if (upload.files.length) file.value = upload.files[0].name;
            });
            row.appendChild(upload);
            document.querySelector('[data-new-demos="' + name + '"]').appendChild(row);
        }
        const removeButton = event.target.closest('.remove-new-score');
        if (removeButton) removeButton.closest('.new-score-row').remove();
    });
})();

(() => {
    const choices = document.querySelectorAll('[data-theme-choice]');
    if (!choices.length) return;

    const applyActiveState = () => {
        const current = document.documentElement.getAttribute('data-theme') || 'auto';
        choices.forEach(choice => {
            choice.classList.toggle('active', choice.dataset.themeChoice === current);
        });
    };

    choices.forEach(choice => {
        choice.addEventListener('click', event => {
            event.preventDefault();
            const value = choice.dataset.themeChoice;
            try {
                if (value === 'auto') {
                    localStorage.removeItem('theme');
                    document.documentElement.removeAttribute('data-theme');
                } else {
                    localStorage.setItem('theme', value);
                    document.documentElement.setAttribute('data-theme', value);
                }
            } catch (e) {}
            applyActiveState();
        });
    });

    applyActiveState();
})();

(() => {
    const box = document.getElementById('quake3-status');
    if (!box) return;

    fetch('/quake3-status.json')
        .then(response => response.ok ? response.json() : [])
        .then(servers => {
            if (!Array.isArray(servers) || servers.length === 0) return;
            box.textContent = '';

            const title = document.createElement('strong');
            title.textContent = box.dataset.quake3Title;
            box.append(title, document.createElement('br'), document.createElement('br'));

            const list = document.createElement('ul');
            list.className = 'quake3-status-list';
            servers.forEach(server => {
                const item = document.createElement('li');
                const dot = document.createElement('span');
                dot.className = 'quake3-status-dot ' + (server.online ? 'quake3-online' : 'quake3-offline');
                item.append(dot, server.label, document.createElement('br'));

                const detail = document.createElement('span');
                detail.className = 'admin-hint';
                if (server.online) {
                    detail.textContent = server.address;
                    item.append(detail);
                    if (typeof server.players === 'number') {
                        const players = document.createElement('span');
                        players.className = 'admin-hint';
                        players.textContent = server.players + (typeof server.maxPlayers === 'number' ? '/' + server.maxPlayers : '') + ' ' + box.dataset.quake3Players;
                        item.append(document.createElement('br'), players);
                    }
                    if (server.map) {
                        const map = document.createElement('span');
                        map.className = 'admin-hint';
                        map.textContent = box.dataset.quake3Map + ': ' + server.map;
                        item.append(document.createElement('br'), map);
                    }
                } else {
                    detail.textContent = box.dataset.quake3Offline;
                    item.append(detail);
                }
                list.appendChild(item);
            });
            box.appendChild(list);
        })
        .catch(() => {});
})();

(() => {
    // Sortable tables on the Quake 3 statistics page. Cells may carry the raw value
    // in data-value (K/D, accuracy, dates); otherwise the visible text is compared.
    const valueOf = (row, index, kind) => {
        const cell = row.cells[index];
        const raw = cell.dataset.value !== undefined ? cell.dataset.value : cell.textContent.trim();
        if (kind !== 'number') return raw.toLowerCase();
        const number = parseFloat(raw);
        return Number.isNaN(number) ? -Infinity : number;
    };

    document.querySelectorAll('table[data-sortable]').forEach(table => {
        const body = table.tBodies[0];
        const headers = Array.from(table.tHead.rows[0].cells);
        headers.forEach((header, index) => {
            const button = header.querySelector('.q3-sort');
            if (!button) return;
            const kind = header.dataset.sort;
            button.addEventListener('click', () => {
                // Numbers start with the highest value, names with A.
                const current = header.getAttribute('aria-sort');
                const descending = current ? current === 'ascending' : kind === 'number';
                headers.forEach(other => other.removeAttribute('aria-sort'));
                header.setAttribute('aria-sort', descending ? 'descending' : 'ascending');

                const rows = Array.from(body.rows);
                rows.sort((a, b) => {
                    const left = valueOf(a, index, kind);
                    const right = valueOf(b, index, kind);
                    const order = left < right ? -1 : left > right ? 1 : 0;
                    return descending ? -order : order;
                });
                rows.forEach(row => body.appendChild(row));
                body.querySelectorAll('.q3-rank').forEach((cell, position) => {
                    cell.textContent = position + 1;
                });
            });
        });
    });
})();

// Match times arrive in German time; show them in the visitor's own time zone,
// same format, with the zone in the tooltip.
(() => {
    const times = document.querySelectorAll('time.q3-local-time[datetime]');
    if (!times.length) return;
    const two = number => String(number).padStart(2, '0');
    let zone = '';
    try {
        zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (e) {
        // an old browser keeps the German time
    }
    times.forEach(time => {
        const date = new Date(time.getAttribute('datetime'));
        if (Number.isNaN(date.getTime())) return;
        time.textContent = two(date.getDate()) + '.' + two(date.getMonth() + 1) + '.'
            + two(date.getFullYear() % 100) + ' ' + two(date.getHours()) + ':' + two(date.getMinutes());
        if (zone) time.title = zone;
    });
})();
