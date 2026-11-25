build/test_text: test_text.cpp ../src/textutil.cpp ../src/rss.cpp ../src/textutil.h ../src/rss.h
	mkdir -p build
	$(CXX) -std=c++17 -Wall -Wextra -I../src test_text.cpp ../src/textutil.cpp ../src/rss.cpp -o $@
	./build/test_text
