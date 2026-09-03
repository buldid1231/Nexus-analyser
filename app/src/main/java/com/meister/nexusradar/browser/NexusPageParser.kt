package com.meister.nexusradar.browser

/**
 * JavaScript is evaluated only inside the Nexus page opened in the embedded WebView.
 * It reads visible mod metadata, but never passwords, form values or cookies.
 */
object NexusPageParser {
    val expandMetadataSections: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const wanted = /^(Nexus requirements|Mods (requiring|using) this (file|mod))(?:\s*\(\d+\))?$/i;
          let clicked = 0;
          for (const element of document.querySelectorAll('button, summary, [role="button"], h2, h3, h4')) {
            if (!wanted.test(clean(element.textContent))) continue;
            const target = element.matches('button, summary, [role="button"]')
              ? element
              : element.closest('button, summary, [role="button"]');
            if (target && target.getAttribute('aria-expanded') !== 'true' && !target.open) {
              try { target.click(); clicked++; } catch (_) {}
            }
          }
          return clicked;
        })();
    """.trimIndent()

    val parseCurrentMod: String = """
        (function() {
          const result = {
            kind: 'mod', mod_id: 0, name: '', author: null, version: null,
            category: null, summary: null, published_at: null, updated_at: null,
            adult: false, url: location.href.split('?')[0].split('#')[0],
            file_size_bytes: null, main_files_count: 0,
            endorsements: null, unique_downloads: null, total_downloads: null,
            tags: [], requirements: [], required_by: [],
            requirements_count: 0, required_by_count: 0,
            content_type: 'MOD', diagnostics: []
          };

          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const text = (element) => element ? clean(element.textContent) : '';
          const uniq = (values) => [...new Set(values.map(clean).filter(Boolean))];
          const hrefId = (href) => {
            const match = String(href || '').match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            return match ? Number(match[1]) : 0;
          };
          const metaProperty = (name) => document.querySelector('meta[property="' + name + '"]')?.content || null;
          const metaName = (name) => document.querySelector('meta[name="' + name + '"]')?.content || null;
          const body = document.body?.innerText || '';

          const leafLabels = (pattern) => [...document.querySelectorAll('h1,h2,h3,h4,h5,dt,th,strong,span,p,div')]
            .filter(element => element.children.length === 0 && pattern.test(text(element)));
          const valueAfterLabel = (pattern) => {
            for (const label of leafLabels(pattern)) {
              const sibling = label.nextElementSibling;
              if (sibling && text(sibling) && !pattern.test(text(sibling))) {
                const time = sibling.matches('time') ? sibling : sibling.querySelector('time');
                return time?.getAttribute('datetime') || text(sibling);
              }
              const parent = label.parentElement;
              if (!parent) continue;
              const time = parent.querySelector('time');
              if (time) return time.getAttribute('datetime') || text(time);
              const values = [...parent.children]
                .filter(child => child !== label)
                .map(text)
                .filter(value => value && !pattern.test(value));
              if (values.length) return values[0];
            }
            return null;
          };
          const normalizeDate = (value) => {
            if (!value) return null;
            const cleaned = clean(value);
            const parsed = new Date(cleaned);
            return isNaN(parsed.getTime()) ? cleaned : parsed.toISOString();
          };
          const parseCount = (value) => {
            if (value == null) return null;
            const normalized = clean(value).replace(/\s/g, '').replace(/,/g, '');
            const match = normalized.match(/([0-9]+(?:\.[0-9]+)?)\s*([KMB])?/i);
            if (!match) return null;
            const multiplier = ({K:1000, M:1000000, B:1000000000})[(match[2] || '').toUpperCase()] || 1;
            return Math.round(Number(match[1]) * multiplier);
          };

          result.mod_id = hrefId(location.href);
          result.name = text(document.querySelector('main h1, h1')) ||
                        clean(metaProperty('og:title')) ||
                        clean(document.title.replace(/ at Skyrim Special Edition Nexus.*$/i, ''));
          result.summary = metaName('description') || metaProperty('og:description') || null;

          const structured = [];
          for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
            try {
              const parsed = JSON.parse(script.textContent || 'null');
              if (Array.isArray(parsed)) structured.push(...parsed);
              else if (parsed) structured.push(parsed);
            } catch (_) {}
          }
          const deepValue = (keys) => {
            const wanted = new Set(keys.map(key => key.toLowerCase()));
            const stack = [...structured];
            let visited = 0;
            while (stack.length && visited < 1000) {
              const current = stack.shift();
              visited++;
              if (!current || typeof current !== 'object') continue;
              for (const [key, value] of Object.entries(current)) {
                if (wanted.has(key.toLowerCase()) && value != null) {
                  if (typeof value === 'object' && value.name) return value.name;
                  if (typeof value !== 'object') return value;
                }
                if (value && typeof value === 'object') {
                  if (Array.isArray(value)) stack.push(...value);
                  else stack.push(value);
                }
              }
            }
            return null;
          };

          const labelledAuthor = valueAfterLabel(/^(Created by|Uploaded by)$/i);
          const structuredAuthor = deepValue(['author', 'creator']);
          const cleanAuthor = clean(labelledAuthor || structuredAuthor);
          result.author = cleanAuthor && !/^(My mods|My profile|Guest)$/i.test(cleanAuthor)
            ? cleanAuthor
            : null;

          const categoryLinks = [...document.querySelectorAll('a[href*="categoryName="]')];
          const categoryLink = categoryLinks.find(anchor => {
            const label = text(anchor);
            return label && !/remove filter|clear|mod categories/i.test(label);
          });
          if (categoryLink) {
            result.category = text(categoryLink);
            if (!result.category) {
              try {
                result.category = clean(new URL(categoryLink.href, location.href).searchParams.get('categoryName'));
              } catch (_) {}
            }
          }
          if (!result.category) result.category = clean(deepValue(['categoryName', 'category'])) || null;

          const tagHeading = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong')]
            .find(heading => /^Tags for this mod$/i.test(text(heading)));
          if (tagHeading) {
            const candidates = [];
            let node = tagHeading.nextElementSibling;
            let walked = 0;
            while (node && walked < 6 && !/^H[1-5]$/.test(node.tagName)) {
              for (const anchor of node.querySelectorAll('a')) {
                const href = anchor.getAttribute('href') || '';
                if (/tag|search/i.test(href)) candidates.push(text(anchor));
              }
              node = node.nextElementSibling;
              walked++;
            }
            if (!candidates.length) {
              const box = tagHeading.closest('section, article') || tagHeading.parentElement;
              for (const anchor of box?.querySelectorAll('a') || []) {
                const href = anchor.getAttribute('href') || '';
                if (/tag|search/i.test(href)) candidates.push(text(anchor));
              }
            }
            result.tags = uniq(candidates).filter(label =>
              label.length <= 80 && !/^(View more|Tag this mod|Manage tags)$/i.test(label)
            );
          }
          if (!result.tags.length) {
            result.tags = uniq([...document.querySelectorAll(
              'a[href*="tag="][href*="skyrimspecialedition"], a[href*="tags="][href*="skyrimspecialedition"]'
            )].map(text)).filter(label => !/^(View more|Tag this mod|Manage tags)$/i.test(label));
          }

          result.version = clean(
            deepValue(['version', 'softwareVersion', 'currentVersion']) ||
            valueAfterLabel(/^(Version|Current version)$/i)
          ) || null;
          result.updated_at = normalizeDate(
            deepValue(['dateModified', 'updatedAt', 'updated_at', 'lastUpdated']) ||
            valueAfterLabel(/^(Last updated|Updated)$/i)
          );
          result.published_at = normalizeDate(
            deepValue(['datePublished', 'createdAt', 'publishedAt', 'published_at']) ||
            valueAfterLabel(/^(Original upload|Uploaded|Published|Created)$/i)
          );
          result.endorsements = parseCount(valueAfterLabel(/^Endorsements?$/i));
          result.unique_downloads = parseCount(valueAfterLabel(/^Unique DLs?$/i));
          result.total_downloads = parseCount(valueAfterLabel(/^Total DLs?$/i));

          const scriptsText = [...document.scripts].map(script => script.textContent || '').join('\n');
          const embeddedValue = (names) => {
            const expression = new RegExp('"(?:' + names.join('|') + ')"\\s*:\\s*"([^"\\\\]+)"', 'i');
            const match = scriptsText.match(expression);
            return match ? match[1] : null;
          };
          if (!result.version) result.version = clean(embeddedValue(['version', 'currentVersion'])) || null;
          if (!result.updated_at) result.updated_at = normalizeDate(embeddedValue(['dateModified', 'updatedAt', 'updated_at', 'lastUpdated']));
          if (!result.published_at) result.published_at = normalizeDate(embeddedValue(['datePublished', 'createdAt', 'publishedAt', 'published_at']));

          if (!result.version) {
            const match = body.match(/(?:^|\n)Version\s*\n+\s*([^\n]{1,40})/i);
            if (match) result.version = clean(match[1]);
          }
          if (!result.updated_at) {
            const match = body.match(/(?:^|\n)Last updated\s*\n+\s*([^\n]{3,80})/i);
            if (match) result.updated_at = normalizeDate(match[1]);
          }
          if (!result.published_at) {
            const match = body.match(/(?:^|\n)Original upload\s*\n+\s*([^\n]{3,80})/i);
            if (match) result.published_at = normalizeDate(match[1]);
          }

          const adultMarker = document.querySelector(
            '[data-adult-content="true"], [data-testid*="adult-warning" i], [class*="adult-warning" i]'
          );
          result.adult = adultMarker !== null ||
            /this mod contains adult content|adult-only content|adult content warning/i.test(body);

          const collectFollowingLinks = (headingPattern) => {
            const output = [];
            const headings = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong,button,summary')]
              .filter(heading => headingPattern.test(text(heading)));
            for (const heading of headings) {
              let node = heading.nextElementSibling;
              let walked = 0;
              while (node && walked < 12) {
                if (/^H[1-5]$/.test(node.tagName)) break;
                for (const anchor of node.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) {
                    output.push({mod_id:id, name:name, url:anchor.href.split('?')[0].split('#')[0]});
                  }
                }
                node = node.nextElementSibling;
                walked++;
              }
              if (!output.length) {
                const parent = heading.closest('section, article, details') || heading.parentElement;
                for (const anchor of parent?.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]') || []) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) {
                    output.push({mod_id:id, name:name, url:anchor.href.split('?')[0].split('#')[0]});
                  }
                }
              }
            }
            const seen = new Set();
            return output.filter(item => !seen.has(item.mod_id) && seen.add(item.mod_id));
          };
          result.requirements = collectFollowingLinks(/^Nexus requirements$/i);
          result.required_by = collectFollowingLinks(/^Mods (requiring|using) this (file|mod)(?:\s*\(\d+\))?$/i);
          result.requirements_count = result.requirements.length;
          result.required_by_count = result.required_by.length;
          const requiredByHeading = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong,button,summary')]
            .map(text)
            .find(label => /^Mods (requiring|using) this (file|mod)(?:\s*\(\d+\))?$/i.test(label));
          const requiredByMatch = String(requiredByHeading || '').match(/\((\d[\d,]*)\)/);
          if (requiredByMatch) {
            result.required_by_count = Math.max(
              result.required_by_count,
              Number(requiredByMatch[1].replace(/,/g, ''))
            );
          }
          const requiredByBodyMatch = body.match(
            /Mods (?:requiring|using) this (?:file|mod)\s*\(([\d,]+)\)/i
          );
          if (requiredByBodyMatch) {
            result.required_by_count = Math.max(
              result.required_by_count,
              Number(requiredByBodyMatch[1].replace(/,/g, ''))
            );
          }

          if (!result.mod_id) result.diagnostics.push('mod_id_missing');
          if (!result.name) result.diagnostics.push('name_missing');
          if (!result.author) result.diagnostics.push('author_missing');
          if (!result.version) result.diagnostics.push('version_missing');
          if (!result.category) result.diagnostics.push('category_missing');
          if (!result.published_at) result.diagnostics.push('published_at_missing');
          if (!result.updated_at) result.diagnostics.push('updated_at_missing');
          return JSON.stringify(result);
        })();
    """.trimIndent()

    val parseFilesTab: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const toBytes = (value) => {
            const normalized = clean(value).replace(',', '.');
            const match = normalized.match(/([0-9]+(?:\.[0-9]+)?)\s*(B|KB|MB|GB|TB|BYTE|BYTES)\b/i);
            if (!match) return null;
            const powers = {B:0, BYTE:0, BYTES:0, KB:1, MB:2, GB:3, TB:4};
            return Math.round(Number(match[1]) * Math.pow(1024, powers[match[2].toUpperCase()] || 0));
          };
          const body = document.body?.innerText || '';
          const start = body.search(/(?:^|\n)Main files\s*(?:\n|$)/i);
          let segment = start >= 0 ? body.slice(start) : body;
          const end = segment.slice(20).search(/(?:^|\n)(Updates|Optional files|Miscellaneous files|Old files|Archived files)\s*(?:\n|$)/i);
          if (end >= 0) segment = segment.slice(0, end + 20);

          const labels = [];
          const expression = /File size\s*(?:\n|:)\s*([0-9]+(?:[.,][0-9]+)?\s*(?:B|KB|MB|GB|TB|Bytes?))/gi;
          let match;
          while ((match = expression.exec(segment)) !== null) labels.push(clean(match[1]));

          if (!labels.length) {
            for (const label of document.querySelectorAll('dt, th, strong, span, div')) {
              if (label.children.length || !/^File size$/i.test(clean(label.textContent))) continue;
              const sibling = label.nextElementSibling;
              if (sibling) labels.push(clean(sibling.textContent));
            }
          }

          const uniqueLabels = [...new Set(labels)];
          const sizes = uniqueLabels.map(toBytes).filter(value => value != null && value >= 0);
          return JSON.stringify({
            file_size_bytes: sizes.length ? Math.max(...sizes) : null,
            main_files_count: sizes.length
          });
        })();
    """.trimIndent()

    val collectVisibleModLinks: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const normalizeDate = (value) => {
            if (!value) return null;
            const cleaned = clean(value);
            const parsed = new Date(cleaned);
            return isNaN(parsed.getTime()) ? cleaned : parsed.toISOString();
          };
          const hrefId = (href) => {
            const match = String(href || '').match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            return match ? Number(match[1]) : 0;
          };
          const findContainer = (anchor) => {
            let node = anchor;
            for (let depth = 0; node && node !== document.body && depth < 9; depth++, node = node.parentElement) {
              const marker = clean(
                (typeof node.className === 'string' ? node.className : '') + ' ' +
                (node.getAttribute?.('data-testid') || '') + ' ' +
                (node.getAttribute?.('data-cy') || '')
              ).toLowerCase();
              if (node.matches?.('article, li') || /mod[-_ ]?(tile|card|item|result|listing|row)/i.test(marker)) {
                return node;
              }
            }
            return anchor.parentElement || anchor;
          };
          const attributeValue = (container, names) => {
            const selector = names.map(name => '[' + name + ']').join(',');
            const nodes = [container, ...container.querySelectorAll(selector)];
            for (const node of nodes) {
              for (const name of names) {
                const value = node.getAttribute?.(name);
                if (value) return value;
              }
            }
            return null;
          };
          const updateFromContainer = (container) => {
            const direct = attributeValue(container, [
              'data-updated-at', 'data-updated', 'data-date-modified', 'data-last-updated'
            ]);
            if (direct) return normalizeDate(direct);

            const times = [...container.querySelectorAll('time')].map(time => ({
              value: time.getAttribute('datetime') || time.getAttribute('title') || clean(time.textContent),
              context: clean(time.parentElement?.textContent)
            })).filter(item => item.value);
            const labelled = times.find(item => /last updated|updated|update|aktualisiert/i.test(item.context));
            if (labelled) return normalizeDate(labelled.value);
            const parsedTimes = times.map(item => ({
              value: item.value,
              stamp: new Date(item.value).getTime()
            })).filter(item => !isNaN(item.stamp));
            if (parsedTimes.length) {
              parsedTimes.sort((a, b) => b.stamp - a.stamp);
              return normalizeDate(parsedTimes[0].value);
            }

            const rawText = container.innerText || container.textContent || '';
            const labelledText = rawText.match(
              /(?:Last\s+updated|Updated|Update|Aktualisiert)\s*(?:\n|:|-)+\s*([^\n]{3,80})/i
            );
            if (labelledText) return normalizeDate(labelledText[1]);
            const embedded = container.innerHTML.match(
              /["'](?:dateModified|updatedAt|updated_at|lastUpdated)["']\s*[:=]\s*["']([^"']+)["']/i
            );
            return embedded ? normalizeDate(embedded[1]) : null;
          };
          const versionFromContainer = (container) => {
            const direct = attributeValue(container, ['data-version', 'data-current-version']);
            if (direct) return clean(direct) || null;
            const rawText = container.innerText || container.textContent || '';
            const labelled = rawText.match(/(?:^|\n)\s*(?:Current\s+)?Version\s*(?:\n|:|-)+\s*([^\n•|]{1,40})/i);
            if (labelled) return clean(labelled[1]);
            const compact = rawText.match(/(?:^|[•|])\s*v(?:ersion)?\s*([0-9][A-Za-z0-9._+\-]{0,39})/i);
            if (compact) return clean(compact[1]);
            const embedded = container.innerHTML.match(
              /["'](?:version|currentVersion)["']\s*[:=]\s*["']([^"']+)["']/i
            );
            return embedded ? clean(embedded[1]) : null;
          };

          const byId = new Map();
          for (const anchor of document.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
            const id = hrefId(anchor.href);
            if (!id) continue;
            const container = findContainer(anchor);
            const heading = container.querySelector('h1, h2, h3, h4, [data-testid*="title"]');
            const anchorText = clean(anchor.textContent);
            const candidate = {
              mod_id: id,
              url: anchor.href.split('?')[0].split('#')[0],
              name: clean(heading?.textContent) ||
                (anchorText.length <= 180 ? anchorText : '') ||
                clean(anchor.getAttribute('aria-label')) || clean(anchor.getAttribute('title')),
              updated_at: updateFromContainer(container),
              version: versionFromContainer(container)
            };
            const previous = byId.get(id);
            const score = (candidate.name ? 1 : 0) + (candidate.version ? 2 : 0) +
              (candidate.updated_at ? 4 : 0);
            const previousScore = previous ?
              (previous.name ? 1 : 0) + (previous.version ? 2 : 0) + (previous.updated_at ? 4 : 0) : -1;
            if (!previous || score > previousScore) byId.set(id, candidate);
          }
          const items = [...byId.values()];
          const next = document.querySelector('link[rel="next"], a[rel="next"]') ||
            [...document.querySelectorAll('a')].find(anchor =>
              /^(next|weiter|nächste|›|»|→)$/i.test(clean(anchor.textContent) || clean(anchor.getAttribute('aria-label')))
            );
          const nextUrl = next?.href && !hrefId(next.href) ? next.href : null;
          return JSON.stringify({kind:'links', url:location.href, next_url:nextUrl, links:items});
        })();
    """.trimIndent()

    val advanceListing: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const visible = (element) => {
            if (!element || element.disabled || element.getAttribute('aria-disabled') === 'true') return false;
            const style = getComputedStyle(element);
            const rect = element.getBoundingClientRect();
            return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
          };
          const pattern = /^(?:(?:load|show|view)\s+(?:\d+\s+)?more(?:\s+mods)?|more\s+mods|mehr\s+(?:\d+\s+)?(?:laden|anzeigen)|next(?:\s+page)?|weiter|nächste(?:\s+seite)?|›|»|→)$/i;
          const controls = [...document.querySelectorAll('button, a[href], [role="button"]')];
          const control = controls.find(element => {
            if (!visible(element) || element.closest('article')) return false;
            const label = clean(element.textContent) || clean(element.getAttribute('aria-label')) ||
              clean(element.getAttribute('title'));
            const marker = clean(
              (element.getAttribute('data-testid') || '') + ' ' +
              (element.getAttribute('data-cy') || '') + ' ' +
              (typeof element.className === 'string' ? element.className : '')
            );
            return pattern.test(label) || /(load|show)[-_ ]?more|pagination[-_ ]?next/i.test(marker);
          });
          if (control) {
            const href = control.href || control.closest('a[href]')?.href || null;
            if (href && href !== location.href && !/\/skyrimspecialedition\/mods\/\d+/i.test(href)) {
              return JSON.stringify({action:'navigate', next_url:href});
            }
            try {
              control.scrollIntoView({block:'center'});
              control.click();
              return JSON.stringify({action:'clicked', next_url:null});
            } catch (_) {}
          }
          const scrolling = document.scrollingElement || document.documentElement;
          const before = scrolling.scrollTop;
          scrolling.scrollTop = scrolling.scrollHeight;
          window.dispatchEvent(new Event('scroll'));
          document.dispatchEvent(new Event('scroll'));
          return JSON.stringify({
            action: scrolling.scrollTop !== before ? 'scrolled' : 'none',
            next_url: null
          });
        })();
    """.trimIndent()
}
