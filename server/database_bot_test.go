package main

import (
	"fmt"
	"testing"
)

// TestPageBoundsCoversEverythingOnce — главное свойство постраничной выдачи:
// проход по всем страницам должен показать каждый доступ ровно один раз.
// Пропуск или задвоение здесь означают, что админ не увидит существующий
// пароль в /list и не сможет его отозвать.
func TestPageBoundsCoversEverythingOnce(t *testing.T) {
	for _, total := range []int{0, 1, 19, 20, 21, 39, 40, 199, 200, 201} {
		seen := make([]int, total)
		_, pages, _, _ := pageBounds(total, passwordsPerPage, 0)

		for page := 0; page < pages; page++ {
			clamped, gotPages, first, last := pageBounds(total, passwordsPerPage, page)
			if clamped != page {
				t.Fatalf("total=%d: страница %d прижата к %d", total, page, clamped)
			}
			if gotPages != pages {
				t.Fatalf("total=%d: число страниц скачет: %d против %d", total, gotPages, pages)
			}
			if last-first > passwordsPerPage {
				t.Fatalf("total=%d стр.%d: на странице %d записей, лимит %d",
					total, page, last-first, passwordsPerPage)
			}
			for i := first; i < last; i++ {
				seen[i]++
			}
		}

		for i, n := range seen {
			if n != 1 {
				t.Fatalf("total=%d: запись %d показана %d раз(а), ожидался ровно 1", total, i, n)
			}
		}
	}
}

func TestPageBoundsClampsOutOfRange(t *testing.T) {
	// Пустой список — одна пустая страница, а не паника и не -1.
	page, pages, first, last := pageBounds(0, 20, 5)
	if page != 0 || pages != 1 || first != 0 || last != 0 {
		t.Fatalf("пустой список: получили page=%d pages=%d [%d,%d)", page, pages, first, last)
	}

	// Страница за концом прижимается к последней (например, доступы удалили,
	// пока админ листал, и старая кнопка «Вперёд ›» указывает в пустоту).
	page, pages, first, last = pageBounds(25, 20, 99)
	if page != 1 || pages != 2 || first != 20 || last != 25 {
		t.Fatalf("за концом: получили page=%d pages=%d [%d,%d)", page, pages, first, last)
	}

	// Отрицательная страница — к началу.
	page, _, first, last = pageBounds(25, 20, -3)
	if page != 0 || first != 0 || last != 20 {
		t.Fatalf("отрицательная: получили page=%d [%d,%d)", page, first, last)
	}
}

// TestSortedPasswordsIsStableAndNatural — порядок должен быть детерминированным
// (иначе страницы разъедутся между вызовами, ведь обход map в Go рандомизирован)
// и человеческим: «Доступ 2» раньше «Доступ 10».
func TestSortedPasswordsIsStableAndNatural(t *testing.T) {
	prev := db
	defer func() { db = prev }()

	db = &Database{Passwords: map[string]*PasswordEntry{}}
	for i := 1; i <= 30; i++ {
		db.Passwords[fmt.Sprintf("pass%02d", i)] = &PasswordEntry{Label: fmt.Sprintf("Доступ %d", i)}
	}

	want := make([]string, 0, 30)
	for i := 1; i <= 30; i++ {
		want = append(want, fmt.Sprintf("Доступ %d", i))
	}

	for attempt := 0; attempt < 20; attempt++ {
		got := sortedPasswordsLocked()
		if len(got) != len(want) {
			t.Fatalf("получили %d паролей, ожидали %d", len(got), len(want))
		}
		for i, pass := range got {
			if label := db.Passwords[pass].Label; label != want[i] {
				t.Fatalf("попытка %d, позиция %d: %q вместо %q", attempt, i, label, want[i])
			}
		}
	}
}

func TestLabelSortKey(t *testing.T) {
	cases := []struct {
		label  string
		prefix string
		num    int64
		ok     bool
	}{
		{"Доступ 10", "Доступ ", 10, true},
		{"Доступ 2", "Доступ ", 2, true},
		{"Доступ …ab12", "Доступ …ab", 12, true},
		{"без цифр", "без цифр", 0, false},
		{"", "", 0, false},
		// Заведомо длинная цифровая часть не должна переполнить int64.
		{"x1234567890123456789012", "x1234567890123456789012", 0, false},
	}
	for _, c := range cases {
		prefix, num, ok := labelSortKey(c.label)
		if prefix != c.prefix || num != c.num || ok != c.ok {
			t.Errorf("labelSortKey(%q) = (%q, %d, %v), ожидалось (%q, %d, %v)",
				c.label, prefix, num, ok, c.prefix, c.num, c.ok)
		}
	}
}
